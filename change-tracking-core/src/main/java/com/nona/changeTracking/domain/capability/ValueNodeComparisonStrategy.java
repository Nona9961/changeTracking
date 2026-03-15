package com.nona.changeTracking.domain.capability;

import com.nona.changeTracking.domain.model.changeset.*;
import com.nona.changeTracking.domain.model.snapshot.*;

import java.util.*;

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
 * 集合项匹配基于 {@link ObjectNode#identifier()} 业务标识符，
 * 允许检测集合中项的新增、删除和修改。
 */
public class ValueNodeComparisonStrategy implements ComparisonStrategy<ValueNodeSnapshot> {

    private static final class VisitingPair {
        private final ValueNode oldNode;
        private final ValueNode newNode;

        private VisitingPair(final ValueNode oldNode, final ValueNode newNode) {
            this.oldNode = oldNode;
            this.newNode = newNode;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof VisitingPair that)) {
                return false;
            }
            return this.oldNode == that.oldNode && this.newNode == that.newNode;
        }

        @Override
        public int hashCode() {
            return 31 * System.identityHashCode(oldNode) + System.identityHashCode(newNode);
        }
    }

    private static final class PositionalIdentity {
        private final int position;

        private PositionalIdentity(final int position) {
            this.position = position;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof PositionalIdentity that)) {
                return false;
            }
            return this.position == that.position;
        }

        @Override
        public int hashCode() {
            return Integer.hashCode(position);
        }

        @Override
        public String toString() {
            return "pos:" + position;
        }
    }

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
        final Set<VisitingPair> visiting = new HashSet<>();
        final List<ChangeNode> children = diffChildren(oldSnapshot.getSnapshotData(), newSnapshot.getSnapshotData(), rootPath, visiting);
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
     * @param visiting 当前递归路径上的节点对（用于循环引用终止）。
     * @return 代表当前路径变更的 ChangeNode 列表（通常只有一个元素或为空）。
     */
    private List<ChangeNode> diffNode(final ValueNode oldNode, final ValueNode newNode, final String path, final Set<VisitingPair> visiting) {
        if (oldNode == newNode) {
            return Collections.emptyList();
        }

        if (oldNode instanceof NullNode && newNode instanceof NullNode) {
            return Collections.emptyList();
        }

        if (oldNode instanceof PrimitiveNode oldPrim && newNode instanceof PrimitiveNode newPrim) {
            if (Objects.equals(oldPrim.value(), newPrim.value())) {
                return Collections.emptyList();
            }
            return List.of(new FieldChangeNode(path, oldPrim.value(), newPrim.value()));
        }

        final VisitingPair pair = new VisitingPair(oldNode, newNode);
        if (!visiting.add(pair)) {
            // 循环引用：同一对节点在当前递归路径上再次出现，终止递归以避免 StackOverflow。
            return Collections.emptyList();
        }

        try {
            final List<ChangeNode> childrenChanges = diffChildren(oldNode, newNode, path, visiting);

            if (!childrenChanges.isEmpty()) {
                return List.of(new ContainerChangeNode(path, childrenChanges));
            }

            // 节点类型不同或为基本类型时，视为字段变更
            final boolean isTypeChanged = !oldNode.getClass().equals(newNode.getClass());
            final boolean isPrimitive = oldNode instanceof PrimitiveNode || oldNode instanceof NullNode;
            if (isTypeChanged || isPrimitive) {
                return List.of(new FieldChangeNode(path, extractValue(oldNode), extractValue(newNode)));
            }

            return Collections.emptyList();
        } finally {
            visiting.remove(pair);
        }
    }

    /**
     * 低层方法：负责遍历和收集。
     * <p>
     * 根据节点类型分发到具体的比较方法，返回子节点的变更列表。
     *
     * @param oldNode 旧节点。
     * @param newNode 新节点。
     * @param path    当前节点的路径。
     * @param visiting 当前递归路径上的节点对（用于循环引用终止）。
     * @return 代表子节点变更的扁平列表。
     */
    private List<ChangeNode> diffChildren(final ValueNode oldNode, final ValueNode newNode, final String path, final Set<VisitingPair> visiting) {
        if (oldNode instanceof ObjectNode oldObj && newNode instanceof ObjectNode newObj) {
            return diffObjectChildren(oldObj, newObj, path, visiting);
        }
        if (oldNode instanceof CollectionNode oldColl && newNode instanceof CollectionNode newColl) {
            return diffCollectionChildren(oldColl, newColl, path, visiting);
        }
        return Collections.emptyList();
    }

    /**
     * 比较两个 ObjectNode 的所有字段。
     *
     * @param oldObj 旧对象节点。
     * @param newObj 新对象节点。
     * @param path   当前对象的路径。
     * @param visiting 当前递归路径上的节点对（用于循环引用终止）。
     * @return 所有字段变更的列表。
     */
    private List<ChangeNode> diffObjectChildren(final ObjectNode oldObj, final ObjectNode newObj, final String path, final Set<VisitingPair> visiting) {
        final List<ChangeNode> changes = new ArrayList<>();
        final Set<String> allKeys = new TreeSet<>();
        allKeys.addAll(oldObj.fields().keySet());
        allKeys.addAll(newObj.fields().keySet());

        for (final String key : allKeys) {
            final ValueNode oldFieldNode = oldObj.fields().getOrDefault(key, new NullNode());
            final ValueNode newFieldNode = newObj.fields().getOrDefault(key, new NullNode());
            final String fieldPath;
            if (path.isEmpty()) {
                fieldPath = key;
            } else {
                fieldPath = path + "." + key;
            }
            changes.addAll(diffNode(oldFieldNode, newFieldNode, fieldPath, visiting));
        }
        return changes;
    }

    /**
     * 比较两个 CollectionNode 的所有项。
     * <p>
     * 使用 {@link ObjectNode#identifier()} 作为项的匹配标识，
     * 检测新增、删除和修改的项。
     * <p>
     * 返回的变更列表中，每个变更的 path 只包含索引部分（如 {@code "[id]"}），
     * 便于在 ChangeSet 转换时计算相对路径。
     *
     * @param oldColl 旧集合节点。
     * @param newColl 新集合节点。
     * @param path    当前集合的路径。
     * @param visiting 当前递归路径上的节点对（用于循环引用终止）。
     * @return 所有集合项变更的列表。
     */
    private List<ChangeNode> diffCollectionChildren(final CollectionNode oldColl, final CollectionNode newColl, final String path, final Set<VisitingPair> visiting) {
        final List<ChangeNode> changes = new ArrayList<>();
        final Map<Object, List<ValueNode>> oldItemsById = groupByIdentity(oldColl.items());
        final Map<Object, List<ValueNode>> newItemsById = groupByIdentity(newColl.items());

        final Set<Object> allIdentities = new HashSet<>();
        allIdentities.addAll(oldItemsById.keySet());
        allIdentities.addAll(newItemsById.keySet());

        for (final Object identity : sortIdentities(allIdentities)) {
            final List<ValueNode> oldItems = oldItemsById.getOrDefault(identity, List.of());
            final List<ValueNode> newItems = newItemsById.getOrDefault(identity, List.of());
            final boolean useOccurrenceSuffix = oldItems.size() > 1 || newItems.size() > 1;

            final int common = Math.min(oldItems.size(), newItems.size());
            for (int index = 0; index < common; index++) {
                final Integer occurrence = toOccurrence(useOccurrenceSuffix, index);
                final String itemPath = buildItemPath(path, identity, occurrence);
                changes.addAll(diffNode(oldItems.get(index), newItems.get(index), itemPath, visiting));
            }

            for (int index = common; index < newItems.size(); index++) {
                final Integer occurrence = toOccurrence(useOccurrenceSuffix, index);
                final String itemPath = buildItemPath(path, identity, occurrence);
                changes.add(new ItemAddedNode(itemPath, newItems.get(index)));
            }

            for (int index = common; index < oldItems.size(); index++) {
                final Integer occurrence = toOccurrence(useOccurrenceSuffix, index);
                final String itemPath = buildItemPath(path, identity, occurrence);
                changes.add(new ItemRemovedNode(itemPath, oldItems.get(index)));
            }
        }

        return changes;
    }

    private Integer toOccurrence(final boolean useOccurrenceSuffix, final int zeroBasedIndex) {
        if (!useOccurrenceSuffix) {
            return null;
        }
        return zeroBasedIndex + 1;
    }

    private List<Object> sortIdentities(final Set<Object> identities) {
        final List<Object> sorted = new ArrayList<>(identities);
        sorted.sort(Comparator.comparing((Object identity) -> identityTypeName(identity))
                .thenComparing(identity -> String.valueOf(identity)));
        return sorted;
    }

    private String identityTypeName(final Object identity) {
        if (identity == null) {
            return "";
        }
        return identity.getClass().getName();
    }

    /**
     * 将集合项按“匹配标识”分组（支持重复项/Null项）。
     * <p>
     * 匹配标识规则：
     * <ul>
     *   <li>{@link ObjectNode} → {@link ObjectNode#identifier()}（允许为 null，例如 Map 的 null key）</li>
     *   <li>{@link PrimitiveNode} → {@link PrimitiveNode#value()}</li>
     *   <li>{@link NullNode} → null</li>
     *   <li>其他类型 → 使用位置标识（pos:n），保证不丢项</li>
     * </ul>
     */
    private Map<Object, List<ValueNode>> groupByIdentity(final Collection<ValueNode> nodes) {
        final Map<Object, List<ValueNode>> result = new LinkedHashMap<>();
        int position = 0;
        for (final ValueNode node : nodes) {
            final Object identity = extractIdentity(node, position++);
            result.computeIfAbsent(identity, ignored -> new ArrayList<>()).add(node);
        }
        return result;
    }

    /**
     * 提取集合项的匹配标识。
     */
    private Object extractIdentity(final ValueNode node, final int position) {
        if (node instanceof ObjectNode objNode) {
            return objNode.identifier();
        }
        if (node instanceof PrimitiveNode primNode) {
            return primNode.value();
        }
        if (node instanceof NullNode) {
            return null;
        }
        return new PositionalIdentity(position);
    }

    /**
     * 构建集合项的路径表示（支持重复项）。
     */
    private String buildItemPath(final String basePath, final Object identity, final Integer occurrence) {
        final String identityText;
        if (identity == null) {
            identityText = "null";
        } else {
            identityText = String.valueOf(identity);
        }
        if (occurrence == null) {
            return basePath + "[" + identityText + "]";
        }
        return basePath + "[" + identityText + "#" + occurrence + "]";
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
