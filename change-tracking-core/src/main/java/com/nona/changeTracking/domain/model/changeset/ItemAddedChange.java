package com.nona.changeTracking.domain.model.changeset;

/**
 * 表示集合项新增（扁平视图）。
 *
 * @param path      相对路径。
 * @param fullPath  完整路径。
 * @param addedItem 新增的项。
 */
public record ItemAddedChange(String path, String fullPath, Object addedItem) implements Change {
}
