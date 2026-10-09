package com.nona.changeTracking.snapshot;

import java.lang.reflect.Array;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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
 * 快照持有的是业务对象引用，值类型可变会导致 track 之后业务修改污染旧快照，
 * 变更静默丢失。可变对象一律按复杂对象脱水展开；
 * {@code AtomicBoolean/AtomicInteger/AtomicLong} 例外：其内部字段受 JDK 模块强封装
 * 无法反射脱水，快照时读取当前值做<b>拷贝</b>（{@link PrimitiveNode} 持有不可变值拷贝，
 * 不持有业务引用），同样满足不可变契约。
 * <p>
 * 支持通过 {@link TrackConfigurable} 配置：
 * <ul>
 *   <li>自定义值类型 - 被视为原始值的额外类型（<b>必须不可变</b>）</li>
 *   <li>自定义值类型包 - 被视为原始值的额外包名</li>
 *   <li>标识符提取器 - 用于集合项匹配的业务标识</li>
 * </ul>
 * <p>
 * <b>类型处理信息复用</b>：字段结构、字段访问准备状态与配置相关的类型规则不再按实例
 * 重复分析。配置无关的元数据经共享的 {@link ReflectionMetadataCache} 按类复用（隐藏字段保持读取
 * 与处理）；值类型分类、值数组分类与标识规则的解析结果经本策略持有的
 * {@link ConfiguredTypeRulesCache} 按类复用并按实例隔离。两套缓存都不保存字段值、标识值或业务实例，
 * 字段值、标识值与提取器执行结果仍按当前对象读取或执行。
 */
public class ValueNodeSnapshotStrategy implements SnapshotStrategy<ValueNodeSnapshot> {

    /**
     * 配置绑定的类型规则缓存：值类型分类、值数组分类与标识规则的解析结果按本策略实例隔离复用。
     * 配置无关的字段结构与字段访问准备状态由共享的 {@link ReflectionMetadataCache} 复用。
     */
    private final ConfiguredTypeRulesCache rulesCache;

    /**
     * 使用指定配置创建快照策略实例。
     *
     * @param configuration 追踪配置，不能为 null。
     * @throws NullPointerException 如果 configuration 为 null。
     */
    public ValueNodeSnapshotStrategy(final TrackConfigurable configuration) {
        Objects.requireNonNull(configuration, "Configuration cannot be null.");
        this.rulesCache = new ConfiguredTypeRulesCache(configuration);
    }

    /**
     * 深拷贝数组（防御拷贝）。
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

        // Atomic* 例外：可变但无法反射脱水（JDK 模块强封装），
        // 快照时读取当前值做拷贝——PrimitiveNode 持有不可变值，不持有业务引用，
        // track 后修改 Atomic 值不会污染旧快照。
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
     * 处理数组。
     * <p>
     * 数组按元素类型分两种语义：
     * <ul>
     *   <li><b>值类型元素</b>（基本类型 / String / 枚举 / 值类型包等，即元素不可变）→
     *       {@link ArrayNode}（数组=值语义，顺序敏感），防御拷贝后传入
     *       （一维浅拷贝、多维递归深拷贝）——数组可变，不拷贝会导致 track 后
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
     * <p>
     * 判定按类按需解析并复用，且与当前配置的值类型分类使用同一份配置结论。
     *
     * @param type 数组类型。
     * @return 值类型数组返回 true。
     */
    private boolean isValueArray(final Class<?> type) {
        return this.rulesCache.isValueArray(type);
    }

    /**
     * 为 Map.Entry 创建 ObjectNode，通过接口方法获取 key/value，避免反射访问 JDK 内部类。
     *
     * @param entry   要转换的 Map.Entry。
     * @param visited 已访问对象的缓存，用于检测循环引用。
     * @return 包含 key/value 字段的 ObjectNode 表示。
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
     * 值类型会被视为原始值，不会递归展开其字段。判断顺序保持既有语义：
     * <ol>
     *   <li>原始类型或包装类</li>
     *   <li>String</li>
     *   <li>枚举</li>
     *   <li>默认值类型包</li>
     *   <li>默认值类型类</li>
     *   <li>用户自定义值类型包</li>
     *   <li>用户自定义值类型类</li>
     * </ol>
     * 判定结果由 {@link ConfiguredTypeRulesCache} 按类按需解析并复用：同一类型的后续实例不重复分类，
     * 不同配置的结论互不污染。
     *
     * @param type 要判断的类型。
     * @return 如果是值类型返回 true。
     */
    private boolean isValueType(final Class<?> type) {
        return this.rulesCache.isValueType(type);
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

        // LinkedHashMap：保字段声明序（元数据为子类→父类序，putIfAbsent 保留先到者）——
        // 比较层 diffObjectChildren 以 ObjectNode 字段迭代序为输出基准，
        // HashMap 会丢失声明序，导致输出顺序与字段声明顺序不一致。
        final Map<String, ValueNode> fieldsMap = new LinkedHashMap<>();
        final ObjectNode objectNode = new ObjectNode(fieldsMap, identifier);
        visited.put(obj, objectNode);

        // 直接向 fieldsMap 填充（先登记后填充：空 map 已入 visited，循环引用返回本节点安全）；
        // 字段结构（子类→父类的非静态字段序列）与字段访问准备状态由共享元数据缓存按类复用，
        // 字段值仍通过已准备的访问按当前对象读取。
        final ReflectionTypeMetadata metadata = ReflectionMetadataCache.SHARED.get(obj.getClass());
        for (int index = 0; index < metadata.size(); index++) {
            final ReflectionTypeMetadata.ReflectionFieldAccess access = metadata.access(index);
            try {
                final ValueNode value = toValueRecursive(access.read(obj), visited);
                // 字段隐藏（子类同名字段覆盖父类字段）：保留更具体类型（子类）先遍历到的值。
                fieldsMap.putIfAbsent(access.fieldName(), value);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Failed to access field: " + access.fieldName(), e);
            }
        }

        return objectNode;
    }

    /**
     * 提取对象的业务标识符。
     * <p>
     * 解析顺序保持既有语义：注册提取器（类链 × 每层接口链查找）→ 提取器执行 → 返回 null 时回退
     * {@link System#identityHashCode(Object)} 包装为 {@link Integer}；未注册提取器的类型直接使用
     * identity 回退规则。
     * <p>
     * 规则解析结果由 {@link ConfiguredTypeRulesCache} 按类复用（含未找到的结果）；提取器本身每次按
     * 当前对象实际执行，执行结果不缓存。返回的标识符对象将直接用于集合项匹配（作为 Map key），
     * 因此必须正确实现 {@link Object#equals(Object)} 和 {@link Object#hashCode()}。
     *
     * @param obj 要提取标识的对象。
     * @return 对象的业务标识符，不会返回 null。
     */
    private Object extractIdentifier(final Object obj) {
        final Object id = this.rulesCache.identifierRule(obj.getClass()).extractor().apply(obj);
        // 已注册提取器返回 null 时仍回退 identityHashCode（与既有一致）；
        // 未找到提取器的类型由规则缓存返回显式的 IDENTITY_FALLBACK 规则。
        if (id != null) {
            return id;
        }
        return System.identityHashCode(obj);
    }
}
