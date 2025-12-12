package com.nona.changeTracking.domain.model.snapshot;

import java.util.Map;

/**
 * 表示复杂对象的快照节点。
 * <p>
 * 包含对象的所有字段及其对应的 {@link ValueNode} 值。
 * 字段名作为 Map 的键，字段值递归表示为 ValueNode。
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
public record ObjectNode(Map<String, ValueNode> fields, Object identifier) implements ValueNode {

    /**
     * 创建一个非集合项的 ObjectNode。
     * <p>
     * identifier 默认为 null，表示此节点不参与集合项匹配。
     *
     * @param fields 对象字段的映射。
     */
    public ObjectNode(Map<String, ValueNode> fields) {
        this(fields, null);
    }
}
