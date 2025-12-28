package com.nona.changeTracking.domain.model.changeset;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 代表一个工作单元内所有变更的集合。
 * <p>
 * 这是框架最终输出的顶层值对象。它提供了获取不同粒度变更视图的方法：
 * <ul>
 *   <li>{@link #getAllChanges()} - 包含容器变更的完整视图</li>
 *   <li>{@link #getLeafChanges()} - 仅包含叶子变更的扁平视图</li>
 * </ul>
 *
 * @param changes 所有被追踪对象的变更列表。
 */
public record ChangeSet(List<ObjectChange> changes) {

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
     *
     * @return 所有变更的列表。
     */
    public List<Change> getAllChanges() {
        final List<Change> allChanges = new ArrayList<>();
        for (final ObjectChange objectChange : this.changes) {
            collectAllChanges(objectChange.changeTree(), allChanges);
        }
        return Collections.unmodifiableList(allChanges);
    }

    /**
     * 只获取最细粒度的、可直接执行的“叶子”变更（字段值的具体变化、集合中项目的增删）。
     * <p>
     * 这个视图适用于需要将变更转换为持久化操作（如 UPDATE, INSERT, DELETE）的场景。
     *
     * @return 最细粒度变更的列表。
     */
    public List<Change> getLeafChanges() {
        final List<Change> leafChanges = new ArrayList<>();
        for (final ObjectChange objectChange : this.changes) {
            collectLeafChanges(objectChange.changeTree(), leafChanges);
        }
        return Collections.unmodifiableList(leafChanges);
    }

    /**
     * 检查此变更集是否为空。
     *
     * @return 如果没有任何变更，则为 true。
     */
    public boolean isEmpty() {
        return changes.isEmpty();
    }

    /**
     * 递归收集所有变更（包括容器变更）。
     *
     * @param node        当前遍历的变更节点。
     * @param accumulator 用于收集变更的列表。
     */
    private void collectAllChanges(final ChangeNode node, final List<Change> accumulator) {
        if (!node.path().isEmpty()) {
            accumulator.add(toChange(node, true));
        }

        if (node instanceof ContainerChangeNode container) {
            for (final ChangeNode child : container.children()) {
                collectAllChanges(child, accumulator);
            }
        }
    }

    /**
     * 递归收集叶子变更（排除容器变更）。
     *
     * @param node        当前遍历的变更节点。
     * @param accumulator 用于收集变更的列表。
     */
    private void collectLeafChanges(final ChangeNode node, final List<Change> accumulator) {
        if (node instanceof ContainerChangeNode container) {
            for (final ChangeNode child : container.children()) {
                collectLeafChanges(child, accumulator);
            }
        } else {
            accumulator.add(toChange(node, false));
        }
    }

    /**
     * 将 ChangeNode 转换为 Change。
     *
     * @param node 要转换的变更节点。
     * @param deep 是否递归转换子节点（仅对 ContainerChangeNode 有效）。
     * @return 转换后的 Change 对象。
     * @throws IllegalStateException 如果遇到未知的 ChangeNode 类型。
     */
    private Change toChange(final ChangeNode node, final boolean deep) {
        if (node instanceof FieldChangeNode fcn) {
            return new FieldChange(fcn.path(), fcn.oldValue(), fcn.newValue());
        }
        if (node instanceof ItemAddedNode ian) {
            return new ItemAddedChange(ian.path(), ian.addedItem());
        }
        if (node instanceof ItemRemovedNode irn) {
            return new ItemRemovedChange(irn.path(), irn.removedItem());
        }
        if (node instanceof ContainerChangeNode ccn) {
            final List<Change> children = deep
                    ? ccn.children().stream().map(child -> toChangeWithRelativePath(child, ccn.path())).collect(Collectors.toList())
                    : Collections.emptyList();
            return new ContainerChange(ccn.path(), children);
        }
        throw new IllegalStateException("Unknown ChangeNode type: " + node.getClass());
    }

    private Change toChangeWithRelativePath(final ChangeNode node, final String parentPath) {
        final String relativePath = toRelativePath(node.path(), parentPath);
        if (node instanceof FieldChangeNode fcn) {
            return new FieldChange(relativePath, fcn.oldValue(), fcn.newValue());
        }
        if (node instanceof ItemAddedNode ian) {
            return new ItemAddedChange(relativePath, ian.addedItem());
        }
        if (node instanceof ItemRemovedNode irn) {
            return new ItemRemovedChange(relativePath, irn.removedItem());
        }
        if (node instanceof ContainerChangeNode ccn) {
            final List<Change> children = ccn.children().stream()
                    .map(child -> toChangeWithRelativePath(child, ccn.path()))
                    .collect(Collectors.toList());
            return new ContainerChange(relativePath, children);
        }
        throw new IllegalStateException("Unknown ChangeNode type: " + node.getClass());
    }

    private String toRelativePath(final String fullPath, final String parentPath) {
        if (parentPath.isEmpty()) {
            return fullPath;
        }
        if (fullPath.startsWith(parentPath + ".")) {
            return fullPath.substring(parentPath.length() + 1);
        }
        if (fullPath.startsWith(parentPath + "[")) {
            return fullPath.substring(parentPath.length());
        }
        return fullPath;
    }
}
