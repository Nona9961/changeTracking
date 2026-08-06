package com.nona.changeTracking.domain.model.changeset;

/**
 * 变更树节点的密封接口（树形视图）。
 * <p>
 * 这是框架内部使用的变更表示，保留了变更的层级结构。
 * 由 {@link com.nona.changeTracking.domain.capability.ComparisonStrategy} 生成。
 * <p>
 * 允许的实现类型：
 * <ul>
 *   <li>{@link FieldChangeNode} - 基本值字段变更（oldValue/newValue 为业务值）</li>
 *   <li>{@link ObjectFieldChangeNode} - 对象/集合字段整体替换（oldNode/newNode 为 ValueNode）</li>
 *   <li>{@link ContainerChangeNode} - 容器节点（包含子变更）</li>
 *   <li>{@link ItemAddedNode} - 集合项新增</li>
 *   <li>{@link ItemRemovedNode} - 集合项删除</li>
 * </ul>
 *
 * @see Change 扁平视图的变更表示
 */
public sealed interface ChangeNode permits FieldChangeNode, ObjectFieldChangeNode, ContainerChangeNode, ItemAddedNode, ItemRemovedNode {

    /**
     * 获取此变更节点的路径。
     * <p>
     * 路径使用点号分隔字段名，使用方括号表示集合项索引。
     * 例如：{@code "address.street"} 或 {@code "items[123]"}。
     *
     * @return 变更节点的路径，根节点为空字符串。
     */
    String path();
}
