package com.nona.changeTracking.domain.model.changeset;

import java.util.List;
import java.util.Objects;

/**
 * 代表一次变更检测计算产出的全部对象变更集合（检测器输出）。
 * <p>
 * 这是框架最终输出的顶层值对象。它提供了获取不同粒度变更视图的方法：
 * <ul>
 *   <li>{@link #getAllChanges()} - 包含容器变更的完整视图</li>
 *   <li>{@link #getLeafChanges()} - 仅包含叶子变更的扁平视图</li>
 * </ul>
 * <p>
 * 两个视图由唯一的转换核心 {@link ChangeViewProjection} 投影：本类只持有
 * 无状态的共享实例并委托，不保留第二份转换算法。投影不缓存转换结果，重复获取按需重新构建。
 * 后序子结果按构造关系共享（子节点引用直接交回父容器组装），不做基于路径、元数据或 children
 * 的等价性判断；不同输出位置是否持有同一 {@link Change} 实例不在契约内。
 *
 * @param changes 所有被追踪对象的变更列表。
 */
public record ChangeSet(List<ObjectChange> changes) {

    /**
     * 两个视图共用的无状态转换核心。
     */
    private static final ChangeViewProjection PROJECTION = new ChangeViewProjection();

    /**
     * 构造一个 ChangeSet。
     * <p>
     * 传入的列表会被复制为不可变列表，确保 ChangeSet 的不可变性。
     *
     * @param changes 包含所有对象变更的列表，不能为 null。
     * @throws NullPointerException 如果 changes 为 null。
     */
    public ChangeSet(final List<ObjectChange> changes) {
        this.changes = List.copyOf(Objects.requireNonNull(changes, "Changes list cannot be null."));
    }

    /**
     * 获取所有变更的扁平化列表，包括容器变更（如对象、列表本身的变化）和叶子变更（字段变化、项目增删）。
     * <p>
     * 这个视图适用于需要了解变更层级结构的场景，如审计日志。
     * <p>
     * 注意：容器变更的 {@link ContainerChange#children()} 是树形嵌套视图（子变更
     * path 为相对路径）；本方法返回的扁平列表是树的前序遍历展平——每个容器和每个
     * 叶子恰好出现一次，同一变更同时出现在容器 children 与扁平列表中属设计语义
     * （树形与扁平双视图）。路径为空的节点不进入扁平列表，其子节点仍按前序出现；容器
     * children 中的空路径节点按原规则保留。
     * <p>
     * 扁平列表的上下文元数据（{@code collectionFieldName}、{@code isParentCollection}）由完整路径
     * 从空上下文解析，因此集合项节点在这里的元数据可能与 {@link #getLeafChanges()} 中同一节点的
     * 元数据不同（叶子视图继承实际父级上下文）。该差异是既有契约，本次投影改造保持不统一。
     *
     * @return 所有变更的只读列表。
     */
    public List<Change> getAllChanges() {
        return PROJECTION.toAllChanges(this.changes);
    }

    /**
     * 只获取最细粒度的、可直接执行的"叶子"变更（字段值的具体变化、集合中项目的增删）。
     * <p>
     * 这个视图适用于需要将变更转换为持久化操作（如 UPDATE, INSERT, DELETE）的场景。
     * <p>
     * 注意：此方法返回的是扁平列表，因此 {@link Change#path()} 与 {@link Change#fullPath()} 保持一致（均为完整路径）。
     * 如需获取相对路径（相对于容器），请使用 {@link #getAllChanges()} 中 {@link ContainerChange#children()} 的子变更。
     * <p>
     * 叶子视图沿原树传递实际父路径与最近的集合字段名，因此路径为空的叶子仍会出现在本视图中（完整视图会
     * 跳过它）；集合项节点的上下文元数据继承自实际父级，与 {@link #getAllChanges()} 扁平列表中同一节点的
     * 元数据可能不同。
     *
     * @return 最细粒度变更的只读列表。
     */
    public List<Change> getLeafChanges() {
        return PROJECTION.toLeafChanges(this.changes);
    }

    /**
     * 检查此变更集是否为空。
     *
     * @return 如果没有任何变更，则为 true。
     */
    public boolean isEmpty() {
        return changes.isEmpty();
    }
}
