package com.nona.changeTracking.domain.model.snapshot;

import java.util.Map;

/**
 * 表示复杂对象的快照节点。
 * <p>
 * 包含对象的所有字段及其对应的 {@link ValueNode} 值。
 * 字段名作为 Map 的键，字段值递归表示为 ValueNode。
 *
 * @param fields           对象字段的映射，键为字段名，值为字段的 ValueNode 表示。
 * @param identityHashCode 对象的标识哈希码，用于集合项匹配。
 *                         对于集合中的项，应使用业务标识的 hashCode；
 *                         对于非集合项，默认为 0。
 */
public record ObjectNode(Map<String, ValueNode> fields, int identityHashCode) implements ValueNode {

    /**
     * 创建一个非集合项的 ObjectNode。
     * <p>
     * identityHashCode 默认为 0，表示此节点不参与集合项匹配。
     *
     * @param fields 对象字段的映射。
     */
    public ObjectNode(Map<String, ValueNode> fields) {
        this(fields, 0);
    }
}
