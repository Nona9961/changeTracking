package com.nona.changeTracking.domain.model.changeset;

/**
 * 表示集合项删除（扁平视图）。
 *
 * @param path        相对路径。
 * @param fullPath    完整路径。
 * @param removedItem 被删除的项。
 */
public record ItemRemovedChange(String path, String fullPath, Object removedItem) implements Change {
}
