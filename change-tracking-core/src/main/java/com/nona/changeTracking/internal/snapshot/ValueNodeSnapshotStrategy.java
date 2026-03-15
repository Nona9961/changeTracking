package com.nona.changeTracking.internal.snapshot;

import com.nona.changeTracking.domain.capability.TrackingConfiguration;
import com.nona.changeTracking.domain.model.snapshot.*;
import com.nona.changeTracking.internal.util.ReflectionUtils;
import com.nona.changeTracking.spi.SnapshotStrategy;

import java.io.File;
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
 *   <li>Collection/Map → {@link CollectionNode}</li>
 *   <li>其他复杂对象 → {@link ObjectNode}</li>
 * </ol>
 * <p>
 * 支持循环引用检测：使用 {@link IdentityHashMap} 缓存已访问对象，
 * 遇到循环引用时返回同一 {@link ValueNode} 实例（包括 {@link ObjectNode} / {@link CollectionNode}）。
 * <p>
 * 支持通过 {@link TrackingConfiguration} 配置：
 * <ul>
 *   <li>自定义值类型 - 被视为原始值的额外类型</li>
 *   <li>自定义值类型包 - 被视为原始值的额外包名</li>
 *   <li>标识符提取器 - 用于集合项匹配的业务标识</li>
 * </ul>
 */
public class ValueNodeSnapshotStrategy implements SnapshotStrategy {

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
            Path.class,
            // java.util.concurrent.atomic
            AtomicBoolean.class,
            AtomicInteger.class,
            AtomicLong.class
    );

    /**
     * 用户配置的自定义值类型。
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

        if (isValueType(type)) {
            return new PrimitiveNode(obj);
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
     *   <li>继承链匹配：遍历父类和接口查找提取器</li>
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
            return id != null ? id : System.identityHashCode(obj);
        }
        return System.identityHashCode(obj);
    }

    /**
     * 在继承链中查找标识提取器。
     * <p>
     * 查找顺序：当前类 → 父类链 → 接口（广度优先）
     *
     * @param type 要查找的类型。
     * @return 找到的提取器，如果没有则返回 null。
     */
    private Function<Object, Object> findExtractor(final Class<?> type) {
        // 1. 精确匹配
        if (this.identifierExtractors.containsKey(type)) {
            return this.identifierExtractors.get(type);
        }

        // 2. 遍历父类链
        Class<?> superClass = type.getSuperclass();
        while (superClass != null && superClass != Object.class) {
            if (this.identifierExtractors.containsKey(superClass)) {
                return this.identifierExtractors.get(superClass);
            }
            superClass = superClass.getSuperclass();
        }

        // 3. 遍历接口（仅直接接口，不递归）
        for (final Class<?> iface : type.getInterfaces()) {
            if (this.identifierExtractors.containsKey(iface)) {
                return this.identifierExtractors.get(iface);
            }
        }

        return null;
    }
}
