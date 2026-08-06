package com.nona.changeTracking.internal.snapshot;

import com.nona.changeTracking.domain.capability.TrackingConfiguration;
import com.nona.changeTracking.domain.model.snapshot.*;
import com.nona.changeTracking.internal.util.ReflectionUtils;
import com.nona.changeTracking.spi.SnapshotStrategy;

import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 基于反射的快照策略实现，将对象转换为 {@link ValueNode} 树。
 * <p>
 * 类型判断顺序：
 * <ol>
 *   <li>null → {@link NullNode}</li>
 *   <li>原始类型/包装类/String/枚举/已知值类型 → {@link PrimitiveNode}</li>
 *   <li>数组（值类型元素 → {@link ArrayNode} 防御拷贝；复杂对象元素 → {@link CollectionNode} 递归）</li>
 *   <li>Collection/Map → {@link CollectionNode}</li>
 *   <li>其他复杂对象 → {@link ObjectNode}</li>
 * </ol>
 * <p>
 * 支持循环引用检测：使用 {@link IdentityHashMap} 缓存已访问对象，
 * 遇到循环引用时返回同一 {@link ValueNode} 实例（包括 {@link ObjectNode} / {@link CollectionNode}）。
 * <p>
 * <b>值类型契约</b>：视为值类型（{@link PrimitiveNode}）的类<b>必须不可变</b>——
 * 快照持有的是业务对象引用，值类型可变会导致 registerClean 之后业务修改污染旧快照，
 * 变更静默丢失。可变对象一律按复杂对象脱水展开；
 * {@code AtomicBoolean/AtomicInteger/AtomicLong} 例外：其内部字段受 JDK 模块强封装
 * 无法反射脱水，快照时读取当前值做<b>拷贝</b>（{@link PrimitiveNode} 持有不可变值拷贝，
 * 不持有业务引用），同样满足不可变契约。
 * <p>
 * 支持通过 {@link TrackingConfiguration} 配置：
 * <ul>
 *   <li>自定义值类型 - 被视为原始值的额外类型（<b>必须不可变</b>）</li>
 *   <li>自定义值类型包 - 被视为原始值的额外包名</li>
 *   <li>标识符提取器 - 用于集合项匹配的业务标识</li>
 * </ul>
 */
public class ValueNodeSnapshotStrategy implements SnapshotStrategy<ValueNodeSnapshot> {

    /**
     * 默认的值类型包名，这些包下的类会被视为原始值。
     * <p>
     * 参考 Jackson 的设计，包含常用的 JDK 值类型包。
     */
    private static final Set<String> DEFAULT_VALUE_PACKAGES = Set.of(
            "java.time",      // LocalDate, LocalDateTime, Instant, Duration, Period, ZonedDateTime, etc.
            "java.math",      // BigInteger, BigDecimal
            "java.net"        // URL, URI, InetAddress, InetSocketAddress
    );

    /**
     * 默认的值类型类，这些类会被视为原始值。
     * <p>
     * 参考 Jackson 的 BasicSerializerFactory，包含常用的 JDK 值类型。
     */
    private static final Set<Class<?>> DEFAULT_VALUE_CLASSES = Set.of(
            // java.util
            UUID.class,
            Locale.class,
            Currency.class,
            // java.util.regex
            Pattern.class,
            // java.io / java.nio
            File.class,
            Path.class
    );

    /**
     * 用户配置的自定义值类型（<b>必须不可变</b>，违反者将污染旧快照导致变更静默丢失）。
     */
    private final Set<Class<?>> customValueTypes;

    /**
     * 用户配置的自定义值类型包。
     */
    private final Set<String> customValuePackages;

    /**
     * 用户配置的标识符提取器。
     */
    private final Map<Class<?>, Function<Object, Object>> identifierExtractors;

    /**
     * 使用指定配置创建快照策略实例。
     *
     * @param configuration 追踪配置，不能为 null。
     * @throws NullPointerException 如果 configuration 为 null。
     */
    public ValueNodeSnapshotStrategy(final TrackingConfiguration configuration) {
        Objects.requireNonNull(configuration, "Configuration cannot be null.");
        this.customValueTypes = configuration.getCustomValueTypes();
        this.customValuePackages = configuration.getCustomValuePackages();
        this.identifierExtractors = configuration.getIdentifierExtractors();
    }

    /**
     * 深拷贝数组（防御拷贝，D14）。
     * <p>
     * 一维：按组件类型创建同类型数组并浅拷贝（元素已判定为值类型=不可变，浅拷贝安全，
     * 且保持运行时数组类型——消费方 {@code (String[]) } 强转可用）；
     * 多维：逐层递归深拷贝（内层行也是数组）。
     *
     * @param array 源数组。
     * @return 内容相同、互不共享引用的新数组。
     */
    private static Object deepCopyArray(final Object array) {
        final Class<?> componentType = array.getClass().getComponentType();
        final int length = Array.getLength(array);

        if (componentType.isArray()) {
            final Object copy = Array.newInstance(componentType, length);
            for (int index = 0; index < length; index++) {
                Array.set(copy, index, deepCopyArray(Array.get(array, index)));
            }
            return copy;
        }

        final Object copy = Array.newInstance(componentType, length);
        System.arraycopy(array, 0, copy, 0, length);
        return copy;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ValueNodeSnapshot createSnapshot(final Object entity) {
        if (entity == null) {
            return new ValueNodeSnapshot(new NullNode());
        }
        final ValueNode rootNode = toValueRecursive(entity, new IdentityHashMap<>());
        return new ValueNodeSnapshot(rootNode);
    }

    /**
     * 递归地将对象转换为 ValueNode。
     *
     * @param obj     要转换的对象。
     * @param visited 已访问对象的缓存，用于检测循环引用。
     * @return 对象的 ValueNode 表示。
     */
    private ValueNode toValueRecursive(final Object obj, final Map<Object, ValueNode> visited) {
        if (obj == null) {
            return new NullNode();
        }
        if (visited.containsKey(obj)) {
            return visited.get(obj);
        }

        final Class<?> type = obj.getClass();

        // Atomic* 例外（D17）：可变但无法反射脱水（JDK 模块强封装），
        // 快照时读取当前值做拷贝——PrimitiveNode 持有不可变值，不持有业务引用，
        // registerClean 后修改 Atomic 值不会污染旧快照。
        if (obj instanceof AtomicBoolean atomicBoolean) {
            return new PrimitiveNode(atomicBoolean.get());
        }
        if (obj instanceof AtomicInteger atomicInteger) {
            return new PrimitiveNode(atomicInteger.get());
        }
        if (obj instanceof AtomicLong atomicLong) {
            return new PrimitiveNode(atomicLong.get());
        }

        if (isValueType(type)) {
            return new PrimitiveNode(obj);
        }

        if (type.isArray()) {
            return processArray(obj, visited);
        }

        if (obj instanceof Collection<?> collection) {
            final List<ValueNode> items = new ArrayList<>(collection.size());
            final CollectionNode collectionNode = new CollectionNode(items);
            visited.put(obj, collectionNode);

            for (final Object item : collection) {
                items.add(toValueRecursive(item, visited));
            }

            return collectionNode;
        }

        if (obj instanceof Map<?, ?> map) {
            final List<ValueNode> items = new ArrayList<>(map.size());
            final CollectionNode mapNode = new CollectionNode(items);
            visited.put(obj, mapNode);

            for (final Map.Entry<?, ?> entry : map.entrySet()) {
                items.add(createMapEntryNode(entry, visited));
            }

            return mapNode;
        }

        return processComplexObject(obj, visited);
    }

    /**
     * 处理数组（A2/D10/D14）。
     * <p>
     * 数组按元素类型分两种语义：
     * <ul>
     *   <li><b>值类型元素</b>（基本类型 / String / 枚举 / 值类型包等，即元素不可变）→
     *       {@link ArrayNode}（数组=值语义，顺序敏感），防御拷贝后传入
     *       （一维浅拷贝、多维递归深拷贝）——数组可变，不拷贝会导致 registerClean 后
     *       业务修改污染旧快照，变更静默丢失</li>
     *   <li><b>复杂对象元素</b> → {@link CollectionNode} 递归展开——复用集合的
     *       identifier 匹配逻辑（{@code extractIdentifier} 使用元素的实际类，
     *       已注册的提取器（如 Order）自动生效，无需数组特配）；数组只是定长有序集合，
     *       Java 数组 vs List 之分是实现细节而非语义</li>
     * </ul>
     *
     * @param array   要处理的数组对象。
     * @param visited 已访问对象的缓存，用于检测循环引用。
     * @return 数组的 ValueNode 表示。
     */
    private ValueNode processArray(final Object array, final Map<Object, ValueNode> visited) {
        if (isValueArray(array.getClass())) {
            return new ArrayNode(deepCopyArray(array));
        }

        final int length = Array.getLength(array);
        final List<ValueNode> items = new ArrayList<>(length);
        final CollectionNode collectionNode = new CollectionNode(items);
        visited.put(array, collectionNode);

        for (int index = 0; index < length; index++) {
            items.add(toValueRecursive(Array.get(array, index), visited));
        }

        return collectionNode;
    }

    /**
     * 判断数组是否为值类型数组（递归检查组件类型链的最底层）。
     * <p>
     * 一维：组件类型是基本类型或值类型（如 {@code byte[]} / {@code String[]}）→ 值数组；
     * 多维：递归到最底层组件类型（如 {@code int[][]} 的最底层是 {@code int}）→ 值数组；
     * 复杂对象数组（如 {@code Order[]} / {@code Order[][]}）→ 非值数组（走 CollectionNode 递归）。
     *
     * @param type 数组类型。
     * @return 值类型数组返回 true。
     */
    private boolean isValueArray(final Class<?> type) {
        Class<?> component = type.getComponentType();
        while (component.isArray()) {
            component = component.getComponentType();
        }
        return component.isPrimitive() || isValueType(component);
    }

    /**
     * 为 Map.Entry 创建 ObjectNode，通过接口方法获取 key/value，避免反射访问 JDK 内部类。
     */
    private ObjectNode createMapEntryNode(final Map.Entry<?, ?> entry, final Map<Object, ValueNode> visited) {
        final Map<String, ValueNode> fields = new HashMap<>();
        fields.put("key", toValueRecursive(entry.getKey(), visited));
        fields.put("value", toValueRecursive(entry.getValue(), visited));
        return new ObjectNode(fields, entry.getKey());
    }

    /**
     * 判断给定类型是否为值类型。
     * <p>
     * 值类型会被视为原始值，不会递归展开其字段。
     * 判断顺序：
     * <ol>
     *   <li>原始类型或包装类</li>
     *   <li>String</li>
     *   <li>枚举</li>
     *   <li>默认值类型包</li>
     *   <li>默认值类型类</li>
     *   <li>用户自定义值类型包</li>
     *   <li>用户自定义值类型类</li>
     * </ol>
     *
     * @param type 要判断的类型。
     * @return 如果是值类型返回 true。
     */
    private boolean isValueType(final Class<?> type) {
        if (ReflectionUtils.isPrimitiveOrWrapper(type)) {
            return true;
        }
        if (type.equals(String.class)) {
            return true;
        }
        if (type.isEnum()) {
            return true;
        }

        final String packageName = type.getPackageName();

        if (DEFAULT_VALUE_PACKAGES.contains(packageName)) {
            return true;
        }
        if (DEFAULT_VALUE_CLASSES.contains(type)) {
            return true;
        }
        if (this.customValuePackages.contains(packageName)) {
            return true;
        }
        return this.customValueTypes.contains(type);
    }

    /**
     * 处理复杂对象，将其转换为 ObjectNode。
     * <p>
     * 循环引用处理逻辑：
     * <ol>
     *   <li>创建空的 fields map</li>
     *   <li>创建 ObjectNode 并放入缓存</li>
     *   <li>递归处理所有字段</li>
     *   <li>将字段填充到 map 中</li>
     * </ol>
     * 这样即使遇到循环引用，也能返回同一个 ObjectNode 实例。
     *
     * @param obj     要处理的复杂对象。
     * @param visited 已访问对象的缓存。
     * @return 对象的 ObjectNode 表示。
     */
    private ObjectNode processComplexObject(final Object obj, final Map<Object, ValueNode> visited) {
        final Object identifier = extractIdentifier(obj);

        final Map<String, ValueNode> fieldsMap = new HashMap<>();
        final ObjectNode objectNode = new ObjectNode(fieldsMap, identifier);
        visited.put(obj, objectNode);

        final Map<String, ValueNode> populatedFields = ReflectionUtils.getAllFields(obj.getClass()).stream()
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .peek(field -> field.setAccessible(true))
                .collect(Collectors.toMap(
                        Field::getName,
                        field -> {
                            try {
                                return toValueRecursive(field.get(obj), visited);
                            } catch (IllegalAccessException e) {
                                throw new IllegalStateException("Failed to access field: " + field.getName(), e);
                            }
                        },
                        // 字段隐藏（子类同名字段覆盖父类字段）：保留更具体类型（子类）先遍历到的值。
                        (existing, ignored) -> existing,
                        LinkedHashMap::new
                ));

        fieldsMap.putAll(populatedFields);

        return objectNode;
    }

    /**
     * 提取对象的业务标识符。
     * <p>
     * 查找顺序：
     * <ol>
     *   <li>精确匹配：查找对象类型的标识提取器</li>
     *   <li>继承链匹配：类链 × 每层接口链统一递归查找提取器</li>
     *   <li>默认值：使用 {@link System#identityHashCode(Object)} 包装为 {@link Integer}</li>
     * </ol>
     * <p>
     * 返回的标识符对象将直接用于集合项匹配（作为 Map key），
     * 因此必须正确实现 {@link Object#equals(Object)} 和 {@link Object#hashCode()}。
     *
     * @param obj 要提取标识的对象。
     * @return 对象的业务标识符，不会返回 null。
     */
    private Object extractIdentifier(final Object obj) {
        final Function<Object, Object> extractor = findExtractor(obj.getClass());
        if (extractor != null) {
            final Object id = extractor.apply(obj);
            // 如果提取器返回 null，回退到 identityHashCode
            if (id != null) {
                return id;
            }
            return System.identityHashCode(obj);
        }
        return System.identityHashCode(obj);
    }

    /**
     * 在继承链中查找标识提取器（类链 × 每层接口链统一递归）。
     * <p>
     * 对 {@code type} 及每个父类（到 Object 为止）：先查该类精确 key，
     * 再递归该类的接口链（接口 + 父接口）查 key。
     * 与父类链对称——父类实现的接口、接口的父接口都能命中，
     * 避免漏检导致回退 identityHashCode（跨会话标识不稳定）。
     *
     * @param type 要查找的类型。
     * @return 找到的提取器，如果没有则返回 null。
     */
    private Function<Object, Object> findExtractor(final Class<?> type) {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            if (this.identifierExtractors.containsKey(current)) {
                return this.identifierExtractors.get(current);
            }
            final Function<Object, Object> interfaceExtractor = findInterfaceExtractor(current, new HashSet<>());
            if (interfaceExtractor != null) {
                return interfaceExtractor;
            }
            current = current.getSuperclass();
        }
        return null;
    }

    /**
     * 递归查找接口链（接口 + 父接口）中的提取器。
     * <p>
     * {@link Class#getInterfaces()} 只返回直接接口，父接口需递归展开；
     * Java 接口支持多继承（菱形），用 visited 集合防环防重复。
     *
     * @param type    当前要展开接口链的类型。
     * @param visited 已访问接口集合（防环）。
     * @return 找到的提取器，如果没有则返回 null。
     */
    private Function<Object, Object> findInterfaceExtractor(final Class<?> type, final Set<Class<?>> visited) {
        for (final Class<?> iface : type.getInterfaces()) {
            if (!visited.add(iface)) {
                continue;
            }
            if (this.identifierExtractors.containsKey(iface)) {
                return this.identifierExtractors.get(iface);
            }
            final Function<Object, Object> parentExtractor = findInterfaceExtractor(iface, visited);
            if (parentExtractor != null) {
                return parentExtractor;
            }
        }
        return null;
    }
}
