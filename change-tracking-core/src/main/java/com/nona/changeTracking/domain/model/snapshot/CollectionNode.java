package com.nona.changeTracking.domain.model.snapshot;

import java.util.Collection;

/**
 * 表示集合类型的快照节点。
 * <p>
 * 用于表示 {@link java.util.Collection} 和 {@link java.util.Map}（作为 Entry 集合）的快照。
 * 集合中的每个元素递归表示为 {@link ValueNode}。
 *
 * @param items 集合中所有元素的 ValueNode 表示。
 */
public record CollectionNode(Collection<ValueNode> items) implements ValueNode {
}
