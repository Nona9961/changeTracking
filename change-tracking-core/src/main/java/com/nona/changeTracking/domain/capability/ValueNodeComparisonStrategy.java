package com.nona.changeTracking.domain.capability;

import com.nona.changeTracking.domain.model.changeset.*;
import com.nona.changeTracking.domain.model.snapshot.*;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ValueNodeComparisonStrategy implements ComparisonStrategy<ValueNodeSnapshot> {

    @Override
    public Class<ValueNodeSnapshot> getSupportedSnapshotType() {
        return ValueNodeSnapshot.class;
    }

    @Override
    public ChangeNode compare(final ValueNodeSnapshot oldSnapshot, final ValueNodeSnapshot newSnapshot) {
        final String rootPath = "";
        final List<ChangeNode> children = diffChildren(oldSnapshot.getSnapshotData(), newSnapshot.getSnapshotData(), rootPath);
        return new ContainerChangeNode(rootPath, children);
    }

    /**
     * 高层方法：负责分发和包裹。
     * 返回一个代表当前 `path` 本身变更的 ChangeNode 列表 (通常只有一个元素或为空)。
     */
    private List<ChangeNode> diffNode(final ValueNode oldNode, final ValueNode newNode, final String path) {
        if (Objects.equals(oldNode, newNode)) {
            return Collections.emptyList();
        }

        // 递归调用低层方法来获取子节点的变更
        final List<ChangeNode> childrenChanges = diffChildren(oldNode, newNode, path);

        // 如果子节点有变更，则将它们包裹在一个代表当前路径的 ContainerChangeNode 中
        if (!childrenChanges.isEmpty()) {
            return List.of(new ContainerChangeNode(path, childrenChanges));
        }

        // 如果子节点无变更，但节点本身不同（例如，从 ObjectNode 变为 PrimitiveNode），则视为字段变更
        if (!oldNode.getClass().equals(newNode.getClass()) || oldNode instanceof PrimitiveNode) {
             return List.of(new FieldChangeNode(path, extractValue(oldNode), extractValue(newNode)));
        }

        return Collections.emptyList();
    }

    /**
     * 低层方法：负责遍历和收集。
     * 返回一个扁平的、代表 `path` 内部所有子节点变更的列表。
     */
    private List<ChangeNode> diffChildren(final ValueNode oldNode, final ValueNode newNode, final String path) {
        if (oldNode instanceof ObjectNode oldObj && newNode instanceof ObjectNode newObj) {
            return diffObjectChildren(oldObj, newObj, path);
        }
        if (oldNode instanceof CollectionNode oldColl && newNode instanceof CollectionNode newColl) {
            return diffCollectionChildren(oldColl, newColl, path);
        }
        // 如果不是容器，就没有子节点
        return Collections.emptyList();
    }

    private List<ChangeNode> diffObjectChildren(final ObjectNode oldObj, final ObjectNode newObj, final String path) {
        final List<ChangeNode> changes = new ArrayList<>();
        final Set<String> allKeys = Stream.concat(oldObj.fields().keySet().stream(), newObj.fields().keySet().stream())
                .collect(Collectors.toSet());

        for (final String key : allKeys) {
            final ValueNode oldFieldNode = oldObj.fields().getOrDefault(key, new NullNode());
            final ValueNode newFieldNode = newObj.fields().getOrDefault(key, new NullNode());
            final String fieldPath = path.isEmpty() ? key : path + "." + key;

            // 对每个子字段，递归调用高层方法
            changes.addAll(diffNode(oldFieldNode, newFieldNode, fieldPath));
        }
        return changes;
    }

    private List<ChangeNode> diffCollectionChildren(final CollectionNode oldColl, final CollectionNode newColl, final String path) {
        final List<ChangeNode> changes = new ArrayList<>();
        final Map<Integer, ValueNode> oldItemsById = mapByIdentity(oldColl.items());
        final Map<Integer, ValueNode> newItemsById = mapByIdentity(newColl.items());

        for (final Map.Entry<Integer, ValueNode> newItemEntry : newItemsById.entrySet()) {
            final int identity = newItemEntry.getKey();
            final ValueNode newItem = newItemEntry.getValue();
            if (!oldItemsById.containsKey(identity)) {
                changes.add(new ItemAddedNode(path, newItem));
            } else {
                final ValueNode oldItem = oldItemsById.get(identity);
                final String itemPath = buildItemPath(path, oldItem);
                // 对被更新的项，递归调用高层方法
                changes.addAll(diffNode(oldItem, newItem, itemPath));
            }
        }

        for (final Map.Entry<Integer, ValueNode> oldItemEntry : oldItemsById.entrySet()) {
            if (!newItemsById.containsKey(oldItemEntry.getKey())) {
                changes.add(new ItemRemovedNode(path, oldItemEntry.getValue()));
            }
        }
        return changes;
    }

    private Map<Integer, ValueNode> mapByIdentity(final Collection<ValueNode> nodes) {
        return nodes.stream()
                .filter(ObjectNode.class::isInstance)
                .map(ObjectNode.class::cast)
                .collect(Collectors.toMap(ObjectNode::identityHashCode, Function.identity(), (a, b) -> a));
    }

    private String buildItemPath(final String basePath, final ValueNode itemNode) {
        if (itemNode instanceof ObjectNode objNode && objNode.fields().containsKey("id")) {
            final ValueNode idNode = objNode.fields().get("id");
            if (idNode instanceof PrimitiveNode primNode && primNode.value() != null) {
                return basePath + "[" + primNode.value() + "]";
            }
        }
        if (itemNode instanceof ObjectNode objNode) {
            return basePath + "[hash:" + objNode.identityHashCode() + "]";
        }
        return basePath + "[hash:" + itemNode.hashCode() + "]";
    }

    private Object extractValue(final ValueNode node) {
        if (node instanceof PrimitiveNode pn) return pn.value();
        if (node instanceof NullNode) return null;
        return node;
    }
}
