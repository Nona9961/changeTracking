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
     * 只获取最细粒度的、可直接执行的"叶子"变更（字段值的具体变化、集合中项目的增删）。
     * <p>
     * 这个视图适用于需要将变更转换为持久化操作（如 UPDATE, INSERT, DELETE）的场景。
     *
     * @return 最细粒度变更的列表。
     */
    public List<Change> getLeafChanges() {
        final List<Change> leafChanges = new ArrayList<>();
        for (final ObjectChange objectChange : this.changes) {
            collectLeafChanges(objectChange.changeTree(), leafChanges, "", null, false);
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
     * @param node                 当前遍历的变更节点。
     * @param accumulator          用于收集变更的列表。
     * @param parentPath           父节点路径。
     * @param collectionFieldName  当前上下文中最近的集合字段名。
     * @param parentIsCollection   父节点是否为集合。
     */
    private void collectLeafChanges(
            final ChangeNode node,
            final List<Change> accumulator,
            final String parentPath,
            final String collectionFieldName,
            final boolean parentIsCollection) {

        final String relativePath = toRelativePath(node.path(), parentPath);
        final boolean currentParentIsCollection = relativePath.startsWith("[");
        final String currentCollectionFieldName = currentParentIsCollection
                ? extractFieldName(parentPath)
                : collectionFieldName;

        if (node instanceof ContainerChangeNode container) {
            for (final ChangeNode child : container.children()) {
                collectLeafChanges(child, accumulator, node.path(), currentCollectionFieldName, currentParentIsCollection);
            }
        } else {
            accumulator.add(toChangeWithContext(node, parentPath, currentCollectionFieldName, currentParentIsCollection));
        }
    }

    /**
     * 将 ChangeNode 转换为 Change（顶层调用）。
     *
     * @param node 要转换的变更节点。
     * @param deep 是否递归转换子节点（仅对 ContainerChangeNode 有效）。
     * @return 转换后的 Change 对象。
     * @throws IllegalStateException 如果遇到未知的 ChangeNode 类型。
     */
    private Change toChange(final ChangeNode node, final boolean deep) {
        final String path = node.path();
        final String fieldName = extractFieldName(path);

        if (node instanceof FieldChangeNode fcn) {
            return new FieldChange(path, path, fieldName, null, false, fcn.oldValue(), fcn.newValue());
        }
        if (node instanceof ItemAddedNode ian) {
            return new ItemAddedChange(path, path, fieldName, null, false, ian.addedItem());
        }
        if (node instanceof ItemRemovedNode irn) {
            return new ItemRemovedChange(path, path, fieldName, null, false, irn.removedItem());
        }
        if (node instanceof ContainerChangeNode ccn) {
            final List<Change> children = deep
                    ? ccn.children().stream()
                            .map(child -> toChangeWithRelativePath(child, ccn.path(), null, false))
                            .collect(Collectors.toList())
                    : Collections.emptyList();
            return new ContainerChange(path, path, fieldName, null, false, children);
        }
        throw new IllegalStateException("Unknown ChangeNode type: " + node.getClass());
    }

    /**
     * 将 ChangeNode 转换为 Change，计算相对路径和上下文元数据。
     *
     * @param node                 要转换的变更节点。
     * @param parentPath           父节点路径。
     * @param collectionFieldName  当前上下文中最近的集合字段名。
     * @param parentIsCollection   父节点是否为集合。
     * @return 转换后的 Change 对象。
     */
    private Change toChangeWithRelativePath(
            final ChangeNode node,
            final String parentPath,
            final String collectionFieldName,
            final boolean parentIsCollection) {

        final String relativePath = toRelativePath(node.path(), parentPath);
        final String fullPath = node.path();
        final String fieldName = extractFieldName(relativePath);

        final boolean currentParentIsCollection = relativePath.startsWith("[");
        final String currentCollectionFieldName = currentParentIsCollection
                ? extractFieldName(parentPath)
                : collectionFieldName;

        if (node instanceof FieldChangeNode fcn) {
            return new FieldChange(relativePath, fullPath, fieldName, currentCollectionFieldName, currentParentIsCollection, fcn.oldValue(), fcn.newValue());
        }
        if (node instanceof ItemAddedNode ian) {
            return new ItemAddedChange(relativePath, fullPath, fieldName, currentCollectionFieldName, currentParentIsCollection, ian.addedItem());
        }
        if (node instanceof ItemRemovedNode irn) {
            return new ItemRemovedChange(relativePath, fullPath, fieldName, currentCollectionFieldName, currentParentIsCollection, irn.removedItem());
        }
        if (node instanceof ContainerChangeNode ccn) {
            final List<Change> children = ccn.children().stream()
                    .map(child -> toChangeWithRelativePath(child, ccn.path(), currentCollectionFieldName, currentParentIsCollection))
                    .collect(Collectors.toList());
            return new ContainerChange(relativePath, fullPath, fieldName, currentCollectionFieldName, currentParentIsCollection, children);
        }
        throw new IllegalStateException("Unknown ChangeNode type: " + node.getClass());
    }

    /**
     * 将 ChangeNode 转换为 Change，使用指定的上下文（用于 getLeafChanges）。
     *
     * @param node                 要转换的变更节点。
     * @param parentPath           父节点路径。
     * @param collectionFieldName  当前上下文中最近的集合字段名。
     * @param parentIsCollection   父节点是否为集合。
     * @return 转换后的 Change 对象。
     */
    private Change toChangeWithContext(
            final ChangeNode node,
            final String parentPath,
            final String collectionFieldName,
            final boolean parentIsCollection) {

        final String relativePath = toRelativePath(node.path(), parentPath);
        final String fullPath = node.path();
        final String fieldName = extractFieldName(relativePath);

        final boolean currentParentIsCollection = relativePath.startsWith("[");
        final String currentCollectionFieldName = currentParentIsCollection
                ? extractFieldName(parentPath)
                : collectionFieldName;

        if (node instanceof FieldChangeNode fcn) {
            return new FieldChange(fullPath, fullPath, fieldName, currentCollectionFieldName, currentParentIsCollection, fcn.oldValue(), fcn.newValue());
        }
        if (node instanceof ItemAddedNode ian) {
            return new ItemAddedChange(fullPath, fullPath, fieldName, currentCollectionFieldName, currentParentIsCollection, ian.addedItem());
        }
        if (node instanceof ItemRemovedNode irn) {
            return new ItemRemovedChange(fullPath, fullPath, fieldName, currentCollectionFieldName, currentParentIsCollection, irn.removedItem());
        }
        throw new IllegalStateException("Unexpected node type in leaf collection: " + node.getClass());
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
        final String lastSegment = lastDotIndex >= 0 ? path.substring(lastDotIndex + 1) : path;

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
