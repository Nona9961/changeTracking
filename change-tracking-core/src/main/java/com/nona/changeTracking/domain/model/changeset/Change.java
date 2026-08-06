package com.nona.changeTracking.domain.model.changeset;

/**
 * 变更的密封接口（扁平视图）。
 * <p>
 * 这是框架对外暴露的变更表示，由 {@link ChangeSet} 从 {@link ChangeNode} 树转换而来。
 * 适用于审计日志、持久化操作等场景。
 * <p>
 * 允许的实现类型：
 * <ul>
 *   <li>{@link ValueChange} - 基本值字段变更（oldValue/newValue 为业务值）</li>
 *   <li>{@link ObjectFieldChange} - 对象/集合字段整体替换（oldNode/newNode 为 ValueNode）</li>
 *   <li>{@link ContainerChange} - 容器变更（仅在 getAllChanges 中出现）</li>
 *   <li>{@link ItemAddedChange} - 集合项新增</li>
 *   <li>{@link ItemRemovedChange} - 集合项删除</li>
 * </ul>
 *
 * @see ChangeNode 树形视图的变更表示
 */
public sealed interface Change permits ValueChange, ObjectFieldChange, ContainerChange, ItemAddedChange, ItemRemovedChange {

    /**
     * 获取此变更的路径。
     * <p>
     * 在树形视图（{@link ContainerChange#children()}）中，子变更的 {@code path()} 为相对路径（相对于当前容器）。
     * 在扁平视图（{@link ChangeSet#getLeafChanges()} / {@link ChangeSet#getAllChanges()} 返回的列表）中，
     * {@code path()} 与 {@link #fullPath()} 保持一致（均为完整路径）。
     *
     * @return 变更路径（相对或完整，取决于视图）。
     */
    String path();

    /**
     * 获取此变更从根到当前节点的完整路径。
     *
     * @return 完整路径，如 {@code "items[1].name"}。
     */
    String fullPath();

    /**
     * 获取此变更的纯字段名（不含索引）。
     * <p>
     * 例如：{@code "items[1]"} 返回 {@code "items"}，
     * {@code "[1]"} 返回 {@code null}，
     * {@code "name"} 返回 {@code "name"}。
     *
     * @return 纯字段名，纯索引路径返回 null。
     */
    String fieldName();

    /**
     * 获取此变更所属的最近一层集合字段名。
     * <p>
     * 例如：{@code "items[1].subItems[101].name"} 返回 {@code "subItems"}，
     * {@code "items[1].name"} 返回 {@code "items"}，
     * {@code "status"} 返回 {@code null}（主表字段）。
     *
     * @return 所属集合字段名，主表字段返回 null。
     */
    String collectionFieldName();

    /**
     * 判断父节点是否为集合。
     *
     * @return 如果父节点是集合则返回 true。
     */
    boolean isParentCollection();
}
