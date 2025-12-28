package com.nona.changeTracking.domain.model.changeset;

/**
 * 表示字段值变更（扁平视图）。
 *
 * @param path     相对路径。
 * @param fullPath 完整路径。
 * @param oldValue 变更前的值。
 * @param newValue 变更后的值。
 */
public record FieldChange(String path, String fullPath, Object oldValue, Object newValue) implements Change {
}
