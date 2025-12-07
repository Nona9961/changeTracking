package com.nona.changeTracking.domain.model.changeset;

/**
 * 表示字段值变更的节点（树形视图）。
 * <p>
 * 这是一个叶子节点，表示某个字段的值从 oldValue 变为 newValue。
 *
 * @param path     字段的路径。
 * @param oldValue 变更前的值。
 * @param newValue 变更后的值。
 */
public record FieldChangeNode(String path, Object oldValue, Object newValue) implements ChangeNode {
}
