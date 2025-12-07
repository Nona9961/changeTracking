package com.nona.changeTracking.domain.model.changeset;

/**
 * 表示字段值变更（扁平视图）。
 * <p>
 * 这是最常见的变更类型，表示某个字段的值从 oldValue 变为 newValue。
 *
 * @param path     字段的路径。
 * @param oldValue 变更前的值。
 * @param newValue 变更后的值。
 */
public record FieldChange(String path, Object oldValue, Object newValue) implements Change {
}
