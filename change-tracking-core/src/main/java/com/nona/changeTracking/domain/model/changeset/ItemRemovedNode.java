package com.nona.changeTracking.domain.model.changeset;

/**
 * 表示集合项删除的节点（树形视图）。
 * <p>
 * 这是一个叶子节点，表示从集合中删除了一个项。
 *
 * @param path        集合的路径。
 * @param removedItem 被删除的项（通常是 {@link com.nona.changeTracking.domain.model.snapshot.ValueNode}）。
 */
public record ItemRemovedNode(String path, Object removedItem) implements ChangeNode {
}
