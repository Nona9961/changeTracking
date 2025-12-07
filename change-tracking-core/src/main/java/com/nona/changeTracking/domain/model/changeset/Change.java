package com.nona.changeTracking.domain.model.changeset;

/**
 * 变更的密封接口（扁平视图）。
 * <p>
 * 这是框架对外暴露的变更表示，由 {@link ChangeSet} 从 {@link ChangeNode} 树转换而来。
 * 适用于审计日志、持久化操作等场景。
 * <p>
 * 允许的实现类型：
 * <ul>
 *   <li>{@link FieldChange} - 字段值变更</li>
 *   <li>{@link ContainerChange} - 容器变更（仅在 getAllChanges 中出现）</li>
 *   <li>{@link ItemAddedChange} - 集合项新增</li>
 *   <li>{@link ItemRemovedChange} - 集合项删除</li>
 * </ul>
 *
 * @see ChangeNode 树形视图的变更表示
 */
public sealed interface Change permits FieldChange, ContainerChange, ItemAddedChange, ItemRemovedChange {

    /**
     * 获取此变更的路径。
     * <p>
     * 路径使用点号分隔字段名，使用方括号表示集合项索引。
     * 例如：{@code "address.street"} 或 {@code "items[123]"}。
     *
     * @return 变更的路径。
     */
    String path();
}
