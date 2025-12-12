package com.nona.changeTracking.domain.capability;

import com.nona.changeTracking.domain.model.changeset.*;
import com.nona.changeTracking.domain.model.snapshot.*;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 基于 {@link ValueNode} 树结构的快照比较策略实现。
 * <p>
 * 此策略通过递归比较两个 {@link ValueNodeSnapshot} 的树结构，
 * 生成描述所有差异的 {@link ChangeNode} 变更树。
 * <p>
 * 比较算法采用双层递归设计：
 * <ul>
 *   <li>{@code diffNode} - 高层方法，负责分发和包裹</li>
 *   <li>{@code diffChildren} - 低层方法，负责遍历和收集</li>
 * </ul>
 * <p>
 * 集合项匹配基于 {@link ObjectNode#identityHashCode()}，
 * 允许检测集合中项的新增、删除和修改。
 */
public class ValueNodeComparisonStrategy implements ComparisonStrategy<ValueNodeSnapshot> {

    /**
     * {@inheritDoc}
     */
    @Override
    public Class<ValueNodeSnapshot> getSupportedSnapshotType() {
        return ValueNodeSnapshot.class;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 比较两个 ValueNode 快照，生成变更树。
     * 返回的根节点是一个 {@link ContainerChangeNode}，包含所有检测到的变更。
     */
    @Override
    public ChangeNode compare(final ValueNodeSnapshot oldSnapshot, final ValueNodeSnapshot newSnapshot) {
        final String rootPath = "";
        final List<ChangeNode> children = diffChildren(oldSnapshot.getSnapshotData(), newSnapshot.getSnapshotData(), rootPath);
        return new ContainerChangeNode(rootPath, children);
    }

    /**
     * 高层方法：负责分发和包裹。
     * <p>
     * 比较两个节点，如果有差异则返回包含变更的列表。
     * 对于容器节点，会递归比较子节点并将结果包裹在 ContainerChangeNode 中。
     *
     * @param oldNode 旧节点。
     * @param newNode 新节点。
     * @param path    当前节点的路径。
     * @return 代表当前路径变更的 ChangeNode 列表（通常只有一个元素或为空）。
     */
    private List<ChangeNode> diffNode(final ValueNode oldNode, final ValueNode newNode, final String path) {
        if (Objects.equals(oldNode, newNode)) {
            return Collections.emptyList();
        }

        final List<ChangeNode> childrenChanges = diffChildren(oldNode, newNode, path);

        if (!childrenChanges.isEmpty()) {
            return List.of(new ContainerChangeNode(path, childrenChanges));
        }

        // 节点类型不同或为基本类型时，视为字段变更
        final boolean isTypeChanged = !oldNode.getClass().equals(newNode.getClass());
        final boolean isPrimitive = oldNode instanceof PrimitiveNode;
        if (isTypeChanged || isPrimitive) {
            return List.of(new FieldChangeNode(path, extractValue(oldNode), extractValue(newNode)));
        }

        return Collections.emptyList();
    }

    /**
     * 低层方法：负责遍历和收集。
     * <p>
     * 根据节点类型分发到具体的比较方法，返回子节点的变更列表。
     *
     * @param oldNode 旧节点。
     * @param newNode 新节点。
     * @param path    当前节点的路径。
     * @return 代表子节点变更的扁平列表。
     */
    private List<ChangeNode> diffChildren(final ValueNode oldNode, final ValueNode newNode, final String path) {
        if (oldNode instanceof ObjectNode oldObj && newNode instanceof ObjectNode newObj) {
            return diffObjectChildren(oldObj, newObj, path);
        }
        if (oldNode instanceof CollectionNode oldColl && newNode instanceof CollectionNode newColl) {
            return diffCollectionChildren(oldColl, newColl, path);
        }
        return Collections.emptyList();
    }

    /**
     * 比较两个 ObjectNode 的所有字段。
     *
     * @param oldObj 旧对象节点。
     * @param newObj 新对象节点。
     * @param path   当前对象的路径。
     * @return 所有字段变更的列表。
     */
    private List<ChangeNode> diffObjectChildren(final ObjectNode oldObj, final ObjectNode newObj, final String path) {
        final List<ChangeNode> changes = new ArrayList<>();
        final Set<String> allKeys = Stream.concat(oldObj.fields().keySet().stream(), newObj.fields().keySet().stream())
                .collect(Collectors.toSet());

        for (final String key : allKeys) {
            final ValueNode oldFieldNode = oldObj.fields().getOrDefault(key, new NullNode());
            final ValueNode newFieldNode = newObj.fields().getOrDefault(key, new NullNode());
            final String fieldPath = path.isEmpty() ? key : path + "." + key;
            changes.addAll(diffNode(oldFieldNode, newFieldNode, fieldPath));
        }
        return changes;
    }

    /**
     * 比较两个 CollectionNode 的所有项。
     * <p>
     * 使用 {@link ObjectNode#identifier()} 作为项的匹配标识，
     * 检测新增、删除和修改的项。
     *
     * @param oldColl 旧集合节点。
     * @param newColl 新集合节点。
     * @param path    当前集合的路径。
     * @return 所有集合项变更的列表。
     */
    private List<ChangeNode> diffCollectionChildren(final CollectionNode oldColl, final CollectionNode newColl, final String path) {
        final List<ChangeNode> changes = new ArrayList<>();
        final Map<Object, ValueNode> oldItemsById = mapByIdentity(oldColl.items());
        final Map<Object, ValueNode> newItemsById = mapByIdentity(newColl.items());

        for (final Map.Entry<Object, ValueNode> newItemEntry : newItemsById.entrySet()) {
            final Object identity = newItemEntry.getKey();
            final ValueNode newItem = newItemEntry.getValue();
            if (!oldItemsById.containsKey(identity)) {
                changes.add(new ItemAddedNode(path, newItem));
            } else {
                final ValueNode oldItem = oldItemsById.get(identity);
                final String itemPath = buildItemPath(path, oldItem);
                changes.addAll(diffNode(oldItem, newItem, itemPath));
            }
        }

        for (final Map.Entry<Object, ValueNode> oldItemEntry : oldItemsById.entrySet()) {
            if (!newItemsById.containsKey(oldItemEntry.getKey())) {
                changes.add(new ItemRemovedNode(path, oldItemEntry.getValue()));
            }
        }
        return changes;
    }

    /**
     * 将集合中的 ObjectNode 按 identifier 映射为 Map。
     * <p>
     * 对于 ObjectNode，使用其 {@link ObjectNode#identifier()} 作为键；
     * 对于 PrimitiveNode，使用其 {@link PrimitiveNode#value()} 作为键；
     * 其他类型（NullNode、CollectionNode）暂不参与匹配。
     *
     * @param nodes 要映射的节点集合。
     * @return 以 identifier 为键的 Map。
     */
    private Map<Object, ValueNode> mapByIdentity(final Collection<ValueNode> nodes) {
        final Map<Object, ValueNode> result = new HashMap<>();
        for (final ValueNode node : nodes) {
            if (node instanceof ObjectNode objNode && objNode.identifier() != null) {
                result.putIfAbsent(objNode.identifier(), node);
            } else if (node instanceof PrimitiveNode primNode && primNode.value() != null) {
                result.putIfAbsent(primNode.value(), node);
            }
            // NullNode 和 CollectionNode 暂不参与基于标识的匹配
        }
        return result;
    }

    /**
     * 构建集合项的路径表示。
     * <p>
     * 对于 ObjectNode，使用其 {@link ObjectNode#identifier()} 生成路径；
     * 对于 PrimitiveNode，使用其值生成路径。
     *
     * @param basePath 集合的基础路径。
     * @param itemNode 集合项节点。
     * @return 集合项的完整路径。
     */
    private String buildItemPath(final String basePath, final ValueNode itemNode) {
        if (itemNode instanceof ObjectNode objNode && objNode.identifier() != null) {
            return basePath + "[" + objNode.identifier() + "]";
        }
        if (itemNode instanceof PrimitiveNode primNode && primNode.value() != null) {
            return basePath + "[" + primNode.value() + "]";
        }
        // 对于无法确定标识的情况，使用 hashCode 作为回退
        return basePath + "[" + itemNode.hashCode() + "]";
    }

    /**
     * 从 ValueNode 中提取原始值。
     *
     * @param node 要提取值的节点。
     * @return 节点的原始值，对于 NullNode 返回 null，对于非基本类型返回节点本身。
     */
    private Object extractValue(final ValueNode node) {
        if (node instanceof PrimitiveNode pn) {
            return pn.value();
        }
        if (node instanceof NullNode) {
            return null;
        }
        return node;
    }
}
