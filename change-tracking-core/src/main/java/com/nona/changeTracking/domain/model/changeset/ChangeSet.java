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
     * <p>
     * 注意：容器变更的 {@link ContainerChange#children()} 是树形嵌套视图（子变更
     * path 为相对路径）；本方法返回的扁平列表是树的前序遍历展平——每个容器和每个
     * 叶子恰好出现一次，同一变更同时出现在容器 children 与扁平列表中属设计语义
     * （D9 双视图）。
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
     * 只获取最细粒度的、可直接执行的"叶子"变更（字段值的具体变化、集合中项目的增删）。
     * <p>
     * 这个视图适用于需要将变更转换为持久化操作（如 UPDATE, INSERT, DELETE）的场景。
     * <p>
     * 注意：此方法返回的是扁平列表，因此 {@link Change#path()} 与 {@link Change#fullPath()} 保持一致（均为完整路径）。
     * 如需获取相对路径（相对于容器），请使用 {@link #getAllChanges()} 中 {@link ContainerChange#children()} 的子变更。
     *
     * @return 最细粒度变更的列表。
     */
    public List<Change> getLeafChanges() {
        final List<Change> leafChanges = new ArrayList<>();
        for (final ObjectChange objectChange : this.changes) {
            collectLeafChanges(objectChange.changeTree(), leafChanges, "", null);
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
     * <p>
     * 每个节点转换为扁平视图 Change（path=完整路径），容器节点的 children
     * 由 {@link #convert(ChangeNode, String, String, boolean)} 内部以树形视图
     * （相对路径）递归转换。
     *
     * @param node        当前遍历的变更节点。
     * @param accumulator 用于收集变更的列表。
     */
    private void collectAllChanges(final ChangeNode node, final List<Change> accumulator) {
        if (!node.path().isEmpty()) {
            accumulator.add(convert(node, "", null, false));
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
     * @param node                 当前遍历的变更节点。
     * @param accumulator          用于收集变更的列表。
     * @param parentPath           父节点路径。
     * @param collectionFieldName  当前上下文中最近的集合字段名。
     */
    private void collectLeafChanges(
            final ChangeNode node,
            final List<Change> accumulator,
            final String parentPath,
            final String collectionFieldName) {

        final String relativePath = toRelativePath(node.path(), parentPath);
        final String currentCollectionFieldName =
                resolveCollectionFieldName(relativePath, parentPath, collectionFieldName);

        if (node instanceof ContainerChangeNode container) {
            for (final ChangeNode child : container.children()) {
                collectLeafChanges(child, accumulator, node.path(), currentCollectionFieldName);
            }
        } else {
            accumulator.add(convert(node, parentPath, currentCollectionFieldName, false));
        }
    }

    /**
     * 将 ChangeNode 转换为 Change（统一核心转换）。
     * <p>
     * 三种视图共享同一转换逻辑（A9 去重）：
     * <ul>
     *   <li>扁平视图（{@link #getAllChanges()} / {@link #getLeafChanges()} 返回的列表）：
     *       useRelativePath=false，path() 与 fullPath() 一致（完整路径）</li>
     *   <li>树形 children 视图（{@link ContainerChange#children()}）：
     *       useRelativePath=true，path() 为相对父容器的路径</li>
     * </ul>
     * <p>
     * 上下文元数据（collectionFieldName / isParentCollection）由相对路径重算，
     * 不依赖调用方传入的父级上下文标记（历史 parentIsCollection 参数从未被消费，
     * 已移除）。
     *
     * @param node                要转换的变更节点。
     * @param parentPath          父节点路径（顶层为空字符串）。
     * @param collectionFieldName 当前上下文中最近的集合字段名（顶层为 null；
     *                            仅在当前路径非集合项时作为继承值）。
     * @param useRelativePath     true 时 path() 使用相对路径（树形 children 视图）；
     *                            false 时 path() 与 fullPath() 一致（扁平视图）。
     * @return 转换后的 Change 对象。
     * @throws IllegalStateException 如果遇到未知的 ChangeNode 类型。
     */
    private Change convert(
            final ChangeNode node,
            final String parentPath,
            final String collectionFieldName,
            final boolean useRelativePath) {

        final String fullPath = node.path();
        final String relativePath = toRelativePath(fullPath, parentPath);
        final String path = useRelativePath ? relativePath : fullPath;
        final String fieldName = extractFieldName(relativePath);
        final boolean currentParentIsCollection = relativePath.startsWith("[");
        final String currentCollectionFieldName =
                resolveCollectionFieldName(relativePath, parentPath, collectionFieldName);

        if (node instanceof FieldChangeNode fcn) {
            return new ValueChange(path, fullPath, fieldName, currentCollectionFieldName, currentParentIsCollection, fcn.oldValue(), fcn.newValue());
        }
        if (node instanceof ObjectFieldChangeNode ofcn) {
            return new ObjectFieldChange(path, fullPath, fieldName, currentCollectionFieldName, currentParentIsCollection, ofcn.oldNode(), ofcn.newNode());
        }
        if (node instanceof ItemAddedNode ian) {
            return new ItemAddedChange(path, fullPath, fieldName, currentCollectionFieldName, currentParentIsCollection, ian.addedItem());
        }
        if (node instanceof ItemRemovedNode irn) {
            return new ItemRemovedChange(path, fullPath, fieldName, currentCollectionFieldName, currentParentIsCollection, irn.removedItem());
        }
        if (node instanceof ContainerChangeNode ccn) {
            final List<Change> children = ccn.children().stream()
                    .map(child -> convert(child, ccn.path(), currentCollectionFieldName, true))
                    .collect(Collectors.toList());
            return new ContainerChange(path, fullPath, fieldName, currentCollectionFieldName, currentParentIsCollection, children);
        }
        throw new IllegalStateException("Unknown ChangeNode type: " + node.getClass());
    }

    /**
     * 解析当前节点最近的集合字段名。
     * <p>
     * 当前节点是集合项（相对路径以 "[" 开头）时，从父路径提取集合字段名；
     * 否则继承调用方传入的上下文值。
     *
     * @param relativePath                 当前节点的相对路径。
     * @param parentPath                   父节点路径。
     * @param inheritedCollectionFieldName 继承的最近集合字段名。
     * @return 当前节点最近的集合字段名（主表字段为 null）。
     */
    private String resolveCollectionFieldName(
            final String relativePath,
            final String parentPath,
            final String inheritedCollectionFieldName) {
        if (relativePath.startsWith("[")) {
            return extractFieldName(parentPath);
        }
        return inheritedCollectionFieldName;
    }

    /**
     * 计算相对路径。
     *
     * @param fullPath   完整路径。
     * @param parentPath 父节点路径。
     * @return 相对于父节点的路径。
     */
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

    /**
     * 从路径中提取纯字段名（不含索引）。
     * <p>
     * 对于嵌套路径如 {@code "items[1].subItems"}，返回最后一段的字段名 {@code "subItems"}。
     *
     * @param path 路径。
     * @return 纯字段名，纯索引路径返回 null。
     */
    private String extractFieldName(final String path) {
        if (path == null || path.isEmpty() || path.startsWith("[")) {
            return null;
        }
        // 找到最后一个 '.' 之后的字段名部分
        final int lastDotIndex = path.lastIndexOf('.');
        final String lastSegment;
        if (lastDotIndex >= 0) {
            lastSegment = path.substring(lastDotIndex + 1);
        } else {
            lastSegment = path;
        }

        // 如果最后一段以 '[' 开头，说明是纯索引
        if (lastSegment.startsWith("[")) {
            return null;
        }

        // 去除可能的索引后缀
        final int bracketIndex = lastSegment.indexOf('[');
        if (bracketIndex > 0) {
            return lastSegment.substring(0, bracketIndex);
        }
        return lastSegment;
    }
}
