package com.nona.changeTracking.domain.model.snapshot;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * 表示复杂对象的快照节点。
 * <p>
 * 包含对象的所有字段及其对应的 {@link ValueNode} 值。
 * 字段名作为 Map 的键，字段值递归表示为 ValueNode。
 * <p>
 * 不可变契约（D11）：
 * <ul>
 *   <li>本类为 final class，内部字段 map 不对外暴露任何集合引用——外部无法获取引用，
 *       写操作在编译级不可能</li>
 *   <li>构造器不拷贝传入的 map：快照构建采用「先登记后填充」流程（支持循环引用），
 *       构造期拷贝会静默丢失填充的字段；调用方必须在构建完成后不再修改传入的 map</li>
 *   <li>{@link #equals(Object)} / {@link #hashCode()} / {@link #toString()} 为内容语义，
 *       并通过 IdentityHashMap 访问集防止循环引用导致的栈溢出</li>
 * </ul>
 */
public final class ObjectNode implements ValueNode {

    /** 对象字段的映射，键为字段名，值为字段的 ValueNode 表示。 */
    private final Map<String, ValueNode> fields;

    /** 对象的业务标识符，用于集合项匹配。 */
    private final Object identifier;

    /**
     * 创建一个 ObjectNode。
     *
     * @param fields     对象字段的映射，键为字段名，值为字段的 ValueNode 表示。
     * @param identifier 对象的业务标识符，用于集合项匹配。
     *                   <p>
     *                   对于配置了标识提取器的类型，使用提取的业务标识（如 ID）；
     *                   对于未配置的类型，默认使用 {@link System#identityHashCode(Object)} 包装为 {@link Integer}；
     *                   对于非集合项，默认为 null。
     *                   <p>
     *                   注意：identifier 对象必须正确实现 {@link Object#equals(Object)} 和 {@link Object#hashCode()}，
     *                   常见类型如 Long、String、UUID 等都满足此要求。
     */
    public ObjectNode(final Map<String, ValueNode> fields, final Object identifier) {
        this.fields = fields;
        this.identifier = identifier;
    }

    /**
     * 创建一个非集合项的 ObjectNode。
     * <p>
     * identifier 默认为 null，表示此节点不参与集合项匹配。
     *
     * @param fields 对象字段的映射。
     */
    public ObjectNode(final Map<String, ValueNode> fields) {
        this(fields, null);
    }

    /**
     * 按字段名取值。
     *
     * @param name 字段名。
     * @return 字段对应的 ValueNode，字段不存在时返回 null。
     */
    public ValueNode field(final String name) {
        return this.fields.get(name);
    }

    /**
     * 只读遍历所有字段。
     *
     * @param consumer 接收字段名和字段值的消费者，不能为 null。
     */
    public void forEachField(final BiConsumer<String, ValueNode> consumer) {
        this.fields.forEach(consumer);
    }

    /**
     * 返回对象的业务标识符。
     *
     * @return 业务标识符，非集合项时为 null。
     */
    public Object identifier() {
        return this.identifier;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 内容语义：identifier 相等且字段映射逐字段内容相等。
     * 通过 IdentityHashMap 访问集终止循环引用递归（已在访问集中的节点视为相等）。
     */
    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ObjectNode that)) {
            return false;
        }
        return equals(that, new IdentityHashMap<>());
    }

    /**
     * 递归比较两个 ObjectNode 的字段内容。
     * <p>
     * 包可见：被 {@link CollectionNode} 的递归比较调用（同包互访），
     * 保证 ObjectNode 与 CollectionNode 交叉引用时仍能正确终止。
     *
     * @param that    待比较的 ObjectNode。
     * @param visited 当前递归路径上的节点访问集（IdentityHashMap，身份比较）。
     * @return 内容相等返回 true。
     */
    boolean equals(final ObjectNode that, final IdentityHashMap<ValueNode, Boolean> visited) {
        if (!Objects.equals(this.identifier, that.identifier)) {
            return false;
        }
        if (this.fields.size() != that.fields.size()) {
            return false;
        }
        for (final Map.Entry<String, ValueNode> entry : this.fields.entrySet()) {
            final ValueNode other = that.fields.get(entry.getKey());
            if (other == null) {
                return false;
            }
            if (!valueEquals(entry.getValue(), other, visited)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 比较两个 ValueNode 的值（内容语义）。
     * <p>
     * 对 ObjectNode/CollectionNode 递归进入各自的 equals(that, visited)；
     * 对 PrimitiveNode/NullNode 直接使用 record 的 equals（值语义）。
     *
     * @param a       左侧节点。
     * @param b       右侧节点。
     * @param visited 当前递归路径上的节点访问集。
     * @return 内容相等返回 true。
     */
    static boolean valueEquals(final ValueNode a, final ValueNode b, final IdentityHashMap<ValueNode, Boolean> visited) {
        if (a == b) {
            return true;
        }
        if (a instanceof ObjectNode objectA && b instanceof ObjectNode objectB) {
            if (visited.containsKey(objectA)) {
                // 循环引用：已在访问集中的节点视为相等，终止递归
                return true;
            }
            visited.put(objectA, Boolean.TRUE);
            try {
                return objectA.equals(objectB, visited);
            } finally {
                visited.remove(objectA);
            }
        }
        if (a instanceof CollectionNode collectionA && b instanceof CollectionNode collectionB) {
            if (visited.containsKey(collectionA)) {
                return true;
            }
            visited.put(collectionA, Boolean.TRUE);
            try {
                return collectionA.equals(collectionB, visited);
            } finally {
                visited.remove(collectionA);
            }
        }
        return a.equals(b);
    }

    /**
     * {@inheritDoc}
     * <p>
     * 内容语义：identifier 的 hashCode 与字段映射的内容 hashCode 的组合。
     * 循环引用路径上的节点返回 0，保证相等对象 hashCode 一致且不栈溢出。
     */
    @Override
    public int hashCode() {
        return hashCode(new IdentityHashMap<>());
    }

    /**
     * 递归计算字段内容 hashCode。
     *
     * @param visited 当前递归路径上的节点访问集。
     * @return 本节点的内容 hashCode。
     */
    int hashCode(final IdentityHashMap<ValueNode, Boolean> visited) {
        if (visited.containsKey(this)) {
            return 0;
        }
        visited.put(this, Boolean.TRUE);
        try {
            int result = Objects.hashCode(this.identifier);
            for (final Map.Entry<String, ValueNode> entry : this.fields.entrySet()) {
                result = 31 * result + entry.getKey().hashCode();
                result = 31 * result + valueHashCode(entry.getValue(), visited);
            }
            return result;
        } finally {
            visited.remove(this);
        }
    }

    /**
     * 计算 ValueNode 的内容 hashCode。
     *
     * @param node    目标节点。
     * @param visited 当前递归路径上的节点访问集。
     * @return 节点的内容 hashCode。
     */
    static int valueHashCode(final ValueNode node, final IdentityHashMap<ValueNode, Boolean> visited) {
        if (node instanceof ObjectNode objectNode) {
            return objectNode.hashCode(visited);
        }
        if (node instanceof CollectionNode collectionNode) {
            return collectionNode.hashCode(visited);
        }
        return node.hashCode();
    }

    /**
     * {@inheritDoc}
     * <p>
     * 内容语义：展示字段映射与 identifier；循环引用路径上的节点以 {@code (cycle)} 表示。
     */
    @Override
    public String toString() {
        return toString(new IdentityHashMap<>());
    }

    /**
     * 递归生成内容字符串。
     *
     * @param visited 当前递归路径上的节点访问集。
     * @return 本节点的内容字符串。
     */
    String toString(final IdentityHashMap<ValueNode, Boolean> visited) {
        if (visited.containsKey(this)) {
            return "(cycle)";
        }
        visited.put(this, Boolean.TRUE);
        try {
            final StringBuilder sb = new StringBuilder("ObjectNode{fields={");
            boolean first = true;
            for (final Map.Entry<String, ValueNode> entry : this.fields.entrySet()) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                sb.append(entry.getKey()).append('=').append(valueToString(entry.getValue(), visited));
            }
            sb.append("}, identifier=").append(this.identifier).append('}');
            return sb.toString();
        } finally {
            visited.remove(this);
        }
    }

    /**
     * 生成 ValueNode 的内容字符串。
     *
     * @param node    目标节点。
     * @param visited 当前递归路径上的节点访问集。
     * @return 节点的内容字符串。
     */
    static String valueToString(final ValueNode node, final IdentityHashMap<ValueNode, Boolean> visited) {
        if (node instanceof ObjectNode objectNode) {
            return objectNode.toString(visited);
        }
        if (node instanceof CollectionNode collectionNode) {
            return collectionNode.toString(visited);
        }
        return String.valueOf(node);
    }
}
