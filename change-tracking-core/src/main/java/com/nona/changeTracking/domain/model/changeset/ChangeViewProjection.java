package com.nona.changeTracking.domain.model.changeset;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 两个变更视图的唯一转换核心。
 * <p>
 * 完整视图（{@link #toAllChanges(List)}）自底向上组装容器：进入路径非空的节点时，先在扁平输出中
 * 预留其条目的位置，待子节点返回各自的表示后再组装本节点的局部表示，并回填该预留位置；因此扁平
 * 列表保持前序，而每个逻辑节点只被转换常数次，而不是被其上方的每个扁平条目重复重建。叶子视图
 * （{@link #toLeafChanges(List)}）遍历同一棵树并携带实际父路径与最近的集合字段名，只对叶子调用
 * 唯一构建核心，不构建它不返回的容器视图。
 * <p>
 * 两个入口共享元数据解析与
 * {@link #buildChange(ChangeNode, String, String, String, String, List)} 的五条 {@link Change}
 * 构造规则，并返回只读列表。本类无状态：不持有任何可变会话字段、转换结果缓存或跨调用状态，因此
 * 重复获取按需重建视图。子结果仅按构造关系共享：子节点的表示按原样交回父节点，不做任何基于相等的
 * 去重；两个输出位置是否持有同一个 {@link Change} 实例不属于契约。
 * <p>
 * 本类保持包内可见：它是 {@link ChangeSet} 的实现细节，不属于本包公开的模型面。
 */
final class ChangeViewProjection {

    /**
     * 创建无状态的转换核心；调用方保持单个共享实例。
     */
    ChangeViewProjection() {
    }

    /**
     * 投影完整视图：树的每个容器与每个叶子，按前序展平，容器子节点保持为相对路径的嵌套树。
     *
     * @param changes 待投影的对象变更，非空
     * @return 不可变的扁平视图，持有路径非空的每个节点恰好一次
     * @throws NullPointerException 当 changes 为 null 时
     */
    public List<Change> toAllChanges(final List<ObjectChange> changes) {
        Objects.requireNonNull(changes, "changes");
        final List<Change> flatOutput = new ArrayList<>();
        for (final ObjectChange objectChange : changes) {
            project(objectChange.changeTree(), "", null, flatOutput);
        }
        return Collections.unmodifiableList(flatOutput);
    }

    /**
     * 投影叶子视图：仅树的叶子，按前序展平，携带实际父路径与从包含节点继承的最近集合字段名。
     *
     * @param changes 待投影的对象变更，非空
     * @return 不可变的扁平视图，持有每个叶子及其完整路径
     * @throws NullPointerException 当 changes 为 null 时
     */
    public List<Change> toLeafChanges(final List<ObjectChange> changes) {
        Objects.requireNonNull(changes, "changes");
        final List<Change> leafOutput = new ArrayList<>();
        for (final ObjectChange objectChange : changes) {
            collectLeaves(objectChange.changeTree(), "", null, leafOutput);
        }
        return Collections.unmodifiableList(leafOutput);
    }

    /**
     * 一次后序遍历返回两种相对表示，分别继承实际父集合上下文和空的父集合上下文。
     * <p>
     * 两种表示均按自身相对路径解析元数据：索引节点重新确定最近集合，字段节点继承父上下文。
     * 父容器直接组装子节点返回的表示，不再递归转换子树。扁平条目从空上下文构建，使用子节点
     * 在空父上下文下的表示作为 children；预留再回填条目位置以保持前序，空路径节点只保留在 children 中。
     *
     * @param node                         待投影的变更节点出现
     * @param parentPath                   包含节点的完整路径，根节点为空串
     * @param inheritedCollectionFieldName 包含节点的最近集合字段名，不在集合内时为 null
     * @param flatOutput                   本节点条目回填到的扁平输出
     * @return 本节点在实际父上下文与空父上下文下的两种相对表示
     */
    private NodeViews project(final ChangeNode node, final String parentPath,
                              final String inheritedCollectionFieldName, final List<Change> flatOutput) {
        final String fullPath = node.path();
        final String relativePath = toRelativePath(fullPath, parentPath);
        final boolean entersFlat = !fullPath.isEmpty();
        final int reserved = entersFlat ? flatOutput.size() : -1;
        if (entersFlat) {
            flatOutput.add(null);
        }

        final String collectionFieldName =
                resolveCollectionFieldName(relativePath, parentPath, inheritedCollectionFieldName);
        final List<Change> inheritedChildren;
        final List<Change> childrenWithoutInheritedContext;
        if (node instanceof ContainerChangeNode container) {
            final List<Change> inherited = new ArrayList<>(container.children().size());
            final List<Change> withoutInheritedContext = collectionFieldName == null
                    ? inherited : new ArrayList<>(container.children().size());
            for (final ChangeNode child : container.children()) {
                final NodeViews childViews = project(child, fullPath, collectionFieldName, flatOutput);
                inherited.add(childViews.inherited());
                if (withoutInheritedContext != inherited) {
                    withoutInheritedContext.add(childViews.withoutInheritedContext());
                }
            }
            inheritedChildren = Collections.unmodifiableList(inherited);
            childrenWithoutInheritedContext = withoutInheritedContext == inherited
                    ? inheritedChildren : Collections.unmodifiableList(withoutInheritedContext);
        } else {
            inheritedChildren = null;
            childrenWithoutInheritedContext = null;
        }

        final Change inherited = buildChange(node, relativePath, fullPath, parentPath,
                inheritedCollectionFieldName, inheritedChildren);
        final Change withoutInheritedContext;
        if (inheritedCollectionFieldName == null || relativePath.startsWith("[")) {
            withoutInheritedContext = inherited;
        } else {
            withoutInheritedContext = buildChange(node, relativePath, fullPath, parentPath,
                    null, childrenWithoutInheritedContext);
        }
        if (entersFlat) {
            flatOutput.set(reserved, buildChange(node, fullPath, fullPath, "", null,
                    childrenWithoutInheritedContext));
        }
        return new NodeViews(inherited, withoutInheritedContext);
    }

    /**
     * 一次节点投影交回父容器的两种相对表示，仅在本次递归组装中使用。
     *
     * @param inherited               继承实际父集合上下文的相对表示
     * @param withoutInheritedContext 从空父集合上下文解析的相对表示，索引节点仍确定自身集合
     */
    private record NodeViews(Change inherited, Change withoutInheritedContext) {
    }

    /**
     * 收集一个节点出现的叶子视图：容器被遍历以收集其叶子后代，叶子以实际父路径与最近的集合字段名
     * 投影。空路径叶子也会被收集；叶子视图保留它们，完整视图跳过它们。
     *
     * @param node                         待收集的变更节点出现
     * @param parentPath                   包含节点的完整路径，根节点为空串
     * @param inheritedCollectionFieldName 包含节点的最近集合字段名，不在集合内时为 null
     * @param leafOutput                   当前投影的叶子输出
     */
    private void collectLeaves(final ChangeNode node, final String parentPath,
                               final String inheritedCollectionFieldName, final List<Change> leafOutput) {
        final String fullPath = node.path();

        if (node instanceof ContainerChangeNode container) {
            final String relativePath = toRelativePath(fullPath, parentPath);
            final String collectionFieldName =
                    resolveCollectionFieldName(relativePath, parentPath, inheritedCollectionFieldName);
            for (final ChangeNode child : container.children()) {
                collectLeaves(child, fullPath, collectionFieldName, leafOutput);
            }
            return;
        }

        leafOutput.add(buildChange(node, fullPath, fullPath, parentPath, inheritedCollectionFieldName, null));
    }

    /**
     * 从节点出现构建一个 {@link Change}：这是两个入口共享的唯一构造核心，五种变更类型的元数据
     * 因此只在一处解析。
     *
     * @param node                         待转换的变更节点出现
     * @param path                         产出的变更所携带的路径，容器子节点中为相对路径，两个扁平视图中为完整路径
     * @param fullPath                     该出现的完整路径
     * @param parentPath                   包含节点的完整路径，根节点为空串
     * @param inheritedCollectionFieldName 包含节点的最近集合字段名，不在集合内时为 null
     * @param children                     容器节点子节点的相对表示，叶子节点为 null
     * @return 持有该出现的元数据、载荷与子节点的变更
     * @throws IllegalStateException 当节点不属于五种变更节点类型时
     */
    private Change buildChange(final ChangeNode node, final String path, final String fullPath,
                               final String parentPath, final String inheritedCollectionFieldName,
                               final List<Change> children) {
        final String relativePath = toRelativePath(fullPath, parentPath);
        final String fieldName = extractFieldName(relativePath);
        final boolean currentParentIsCollection = relativePath.startsWith("[");
        final String collectionFieldName =
                resolveCollectionFieldName(relativePath, parentPath, inheritedCollectionFieldName);

        if (node instanceof FieldChangeNode fcn) {
            return new ValueChange(path, fullPath, fieldName, collectionFieldName,
                    currentParentIsCollection, fcn.oldValue(), fcn.newValue());
        }
        if (node instanceof ObjectFieldChangeNode ofcn) {
            return new ObjectFieldChange(path, fullPath, fieldName, collectionFieldName,
                    currentParentIsCollection, ofcn.oldNode(), ofcn.newNode());
        }
        if (node instanceof ItemAddedNode ian) {
            return new ItemAddedChange(path, fullPath, fieldName, collectionFieldName,
                    currentParentIsCollection, ian.addedItem());
        }
        if (node instanceof ItemRemovedNode irn) {
            return new ItemRemovedChange(path, fullPath, fieldName, collectionFieldName,
                    currentParentIsCollection, irn.removedItem());
        }
        if (node instanceof ContainerChangeNode) {
            return new ContainerChange(path, fullPath, fieldName, collectionFieldName,
                    currentParentIsCollection, children);
        }
        throw new IllegalStateException("Unknown ChangeNode type: " + node.getClass());
    }

    /**
     * 解析节点相对于其包含节点的路径。
     *
     * @param fullPath   节点的完整路径
     * @param parentPath 包含节点的完整路径，根节点为空串
     * @return 节点的相对路径
     */
    private static String toRelativePath(final String fullPath, final String parentPath) {
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
     * 解析路径的纯字段名：不含索引的字段名，纯索引路径为 null。
     *
     * @param path 待解析的路径
     * @return 纯字段名，路径不携带字段名时为 null
     */
    private static String extractFieldName(final String path) {
        if (path == null || path.isEmpty() || path.startsWith("[")) {
            return null;
        }
        final int lastDotIndex = path.lastIndexOf('.');
        final String lastSegment;
        if (lastDotIndex >= 0) {
            lastSegment = path.substring(lastDotIndex + 1);
        } else {
            lastSegment = path;
        }

        if (lastSegment.startsWith("[")) {
            return null;
        }

        final int bracketIndex = lastSegment.indexOf('[');
        if (bracketIndex > 0) {
            return lastSegment.substring(0, bracketIndex);
        }
        return lastSegment;
    }

    /**
     * 解析节点的最近集合字段名：以下标开头的相对路径从包含节点的路径解析，其余路径继承其包含节点
     * 的上下文。
     *
     * @param relativePath                 节点相对于其包含节点的路径
     * @param parentPath                   包含节点的完整路径，根节点为空串
     * @param inheritedCollectionFieldName 包含节点的最近集合字段名，不在集合内时为 null
     * @return 最近的集合字段名，不在集合内时为 null
     */
    private static String resolveCollectionFieldName(final String relativePath, final String parentPath,
                                                     final String inheritedCollectionFieldName) {
        if (relativePath.startsWith("[")) {
            return extractFieldName(parentPath);
        }
        return inheritedCollectionFieldName;
    }
}
