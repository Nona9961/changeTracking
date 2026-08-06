package com.nona.changeTracking.domain.model.changeset;

import com.nona.changeTracking.domain.model.snapshot.ValueNode;

/**
 * 表示集合项新增的节点（树形视图）。
 * <p>
 * 这是一个叶子节点，表示在集合中新增了一个项。
 *
 * @param path      集合的路径。
 * @param addedItem 新增项的 ValueNode 表示。
 */
public record ItemAddedNode(String path, ValueNode addedItem) implements ChangeNode {
}
