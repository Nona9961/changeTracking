package com.nona.changeTracking.domain.model.changeset;

/**
 * 表示集合项新增的节点（树形视图）。
 * <p>
 * 这是一个叶子节点，表示在集合中新增了一个项。
 *
 * @param path      集合的路径。
 * @param addedItem 新增的项（通常是 {@link com.nona.changeTracking.domain.model.snapshot.ValueNode}）。
 */
public record ItemAddedNode(String path, Object addedItem) implements ChangeNode {
}
