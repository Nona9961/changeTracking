package com.nona.changeTracking.domain.model.changeset;

import com.nona.changeTracking.domain.model.snapshot.ValueNode;

/**
 * 表示集合项删除的节点（树形视图）。
 * <p>
 * 这是一个叶子节点，表示从集合中删除了一个项。
 *
 * @param path        集合的路径。
 * @param removedItem 被删除项的 ValueNode 表示。
 */
public record ItemRemovedNode(String path, ValueNode removedItem) implements ChangeNode {
}
