package com.nona.changeTracking.domain.model.changeset;

/**
 * 表示集合项删除（扁平视图）。
 * <p>
 * 表示从集合中删除了一个项。
 *
 * @param path        集合的路径。
 * @param removedItem 被删除的项。
 */
public record ItemRemovedChange(String path, Object removedItem) implements Change {
}
