package com.nona.changeTracking.domain.capability;

import com.nona.changeTracking.domain.model.changeset.Change;
import com.nona.changeTracking.domain.model.changeset.ChangeNode;
import com.nona.changeTracking.domain.model.changeset.ChangeSet;
import com.nona.changeTracking.domain.model.changeset.ContainerChangeNode;
import com.nona.changeTracking.domain.model.changeset.FieldChangeNode;
import com.nona.changeTracking.domain.model.changeset.ItemAddedNode;
import com.nona.changeTracking.domain.model.changeset.ItemRemovedChange;
import com.nona.changeTracking.domain.model.changeset.ItemRemovedNode;
import com.nona.changeTracking.domain.model.changeset.ObjectChange;
import com.nona.changeTracking.domain.model.changeset.ValueChange;
import com.nona.changeTracking.domain.model.snapshot.CollectionNode;
import com.nona.changeTracking.domain.model.snapshot.NullNode;
import com.nona.changeTracking.domain.model.snapshot.ObjectNode;
import com.nona.changeTracking.domain.model.snapshot.PrimitiveNode;
import com.nona.changeTracking.domain.model.snapshot.ValueNode;
import com.nona.changeTracking.domain.model.snapshot.ValueNodeSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ValueNodeComparisonStrategy} 路径语义单元测试（US01、US05）。
 * <p>
 * 通过公开 {@code compare} 验证：重复标识的出现序后缀、输出顺序、标识来源、路径文本格式、
 * 退出路径恢复、零变更遍历不格式化标识（AC05.1）以及既有循环终止语义；并通过 {@link ChangeSet}
 * 两种视图核对路径与上下文元数据（AC05.3）。
 */
@DisplayName("ValueNodeComparisonStrategy 路径语义单元测试")
class ValueNodeComparisonStrategyPathUnitTest {

    /**
     * 每次测试新建的比较策略。
     */
    private ValueNodeComparisonStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new ValueNodeComparisonStrategy();
        CountingId.TO_STRING_CALLS.set(0);
    }

    @Nested
    @DisplayName("重复标识与出现序后缀")
    class DuplicateIdentifierPaths {

        @Test
        @DisplayName("两侧重复标识的共同配对应按出现序加后缀并逐对比较")
        void matchedDuplicatePairs_shouldSuffixBothPaths() {
            final ChangeNode root = strategy.compare(
                    snap(rootWithItems(item("A", "v1"), item("A", "v2"))),
                    snap(rootWithItems(item("A", "v3"), item("A", "v4"))));

            final ContainerChangeNode items = itemsChangeOf(root);
            assertThat(items.children()).extracting(ChangeNode::path)
                    .containsExactly("items[A#1]", "items[A#2]");
            assertThat(((ContainerChangeNode) items.children().get(0)).children().get(0))
                    .isEqualTo(new FieldChangeNode("items[A#1].value", "v1", "v3"));
            assertThat(((ContainerChangeNode) items.children().get(1)).children().get(0))
                    .isEqualTo(new FieldChangeNode("items[A#2].value", "v2", "v4"));
        }

        @Test
        @DisplayName("重复标识的多余旧项应按出现序加后缀")
        void surplusOldDuplicate_shouldCarryOccurrenceSuffix() {
            final ChangeNode root = strategy.compare(
                    snap(rootWithItems(item("A", "v1"), item("A", "v2"))),
                    snap(rootWithItems(item("A", "v1"))));

            final ContainerChangeNode items = itemsChangeOf(root);
            assertThat(items.children()).hasSize(1);
            assertThat(items.children().get(0)).isInstanceOf(ItemRemovedNode.class);
            assertThat(items.children().get(0).path()).isEqualTo("items[A#2]");
        }

        @Test
        @DisplayName("唯一标识的配对不应加后缀")
        void uniqueMatch_shouldNotCarrySuffix() {
            final ChangeNode root = strategy.compare(
                    snap(rootWithItems(item("A", "v1"))),
                    snap(rootWithItems(item("A", "v2"))));

            final ContainerChangeNode items = itemsChangeOf(root);
            assertThat(items.children()).extracting(ChangeNode::path).containsExactly("items[A]");
            assertThat(((ContainerChangeNode) items.children().get(0)).children().get(0).path())
                    .isEqualTo("items[A].value");
        }
    }

    @Nested
    @DisplayName("输出顺序")
    class Ordering {

        @Test
        @DisplayName("集合变更顺序应为旧侧出现序在前、新独有项追加在后")
        void collectionChangeOrder_shouldBeOldFirstThenNewOnly() {
            final ChangeNode root = strategy.compare(
                    snap(rootWithItems(item("B", "b1"), item("A", "a1"))),
                    snap(rootWithItems(item("B", "b2"), item("A", "a2"), item("C", "c1"))));

            final ContainerChangeNode items = itemsChangeOf(root);
            assertThat(items.children()).extracting(ChangeNode::path)
                    .containsExactly("items[B]", "items[A]", "items[C]");
            assertThat(items.children().get(2)).isInstanceOf(ItemAddedNode.class);
        }
    }

    @Nested
    @DisplayName("标识来源与路径文本格式")
    class IdentitySourceAndText {

        @Test
        @DisplayName("等值标识实例不同时，路径应使用旧侧首次出现的文本")
        void equalIdentitiesWithDifferentText_shouldUseOldTextForMatchedPair() {
            final ChangeNode root = strategy.compare(
                    snap(rootWithItems(item(new DivergentId("A", "old-label"), "v1"))),
                    snap(rootWithItems(item(new DivergentId("A", "new-label"), "v2"))));

            final List<String> paths = allPathsOf(root);
            assertThat(paths).contains("items[old-label]", "items[old-label].value");
            assertThat(paths).noneMatch(path -> path.contains("new-label"));
        }

        @Test
        @DisplayName("新独有标识的路径应使用新侧文本")
        void newExclusiveIdentity_shouldUseNewText() {
            final ChangeNode root = strategy.compare(
                    snap(rootWithItems()),
                    snap(rootWithItems(item(new DivergentId("C", "new-only"), "v1"))));

            final ContainerChangeNode items = itemsChangeOf(root);
            assertThat(items.children().get(0).path()).isEqualTo("items[new-only]");
        }

        @Test
        @DisplayName("null 标识项删除应呈现为 [null]")
        void nullIdentityItem_shouldRenderNullText() {
            final ChangeNode root = strategy.compare(
                    snap(new ObjectNode(Map.of("items", new CollectionNode(List.of(new NullNode()))))),
                    snap(new ObjectNode(Map.of("items", new CollectionNode(List.of())))));

            final ContainerChangeNode items = itemsChangeOf(root);
            assertThat(items.children().get(0)).isInstanceOf(ItemRemovedNode.class);
            assertThat(items.children().get(0).path()).isEqualTo("items[null]");
        }

        @Test
        @DisplayName("无业务标识的集合项应以位置文本 pos:n 输出路径")
        void positionalIdentityItem_shouldRenderPositionText() {
            final ChangeNode root = strategy.compare(
                    snap(new ObjectNode(Map.of("outer", new CollectionNode(List.of(
                            new CollectionNode(List.of(new PrimitiveNode("a")))))))),
                    snap(new ObjectNode(Map.of("outer", new CollectionNode(List.of(
                            new CollectionNode(List.of(new PrimitiveNode("b")))))))));

            final List<String> paths = allPathsOf(root);
            assertThat(paths).contains("outer[pos:0]", "outer[pos:0][a]", "outer[pos:0][b]");
        }

        @Test
        @DisplayName("同一集合中的重复后缀与 null 文本应并存且保持顺序")
        void mixedSuffixAndNullFormats_shouldCoexist() {
            final ChangeNode root = strategy.compare(
                    snap(new ObjectNode(Map.of("items", new CollectionNode(List.of(
                            item("A", "a1"), item("A", "a2"), new NullNode()))))),
                    snap(rootWithItems(item("A", "a1"))));

            final ContainerChangeNode items = itemsChangeOf(root);
            assertThat(items.children()).extracting(ChangeNode::path)
                    .containsExactly("items[A#2]", "items[null]");
        }
    }

    @Nested
    @DisplayName("零变更遍历不格式化标识（AC05.1）")
    class NoFormatOnZeroChange {

        @Test
        @DisplayName("内容相等的集合项零变更遍历不应调用标识 toString")
        void zeroChangeTraversal_shouldNotFormatAnyIdentifier() {
            final ChangeNode root = strategy.compare(
                    snap(rootWithItems(item(new CountingId("A"), "v1"),
                            item(new CountingId("B"), "v1"))),
                    snap(rootWithItems(item(new CountingId("A"), "v1"),
                            item(new CountingId("B"), "v1"))));

            final int calls = CountingId.TO_STRING_CALLS.get();
            assertThat(childrenOf(root)).isEmpty();
            assertThat(calls).isZero();
        }

        @Test
        @DisplayName("只有变更项被格式化标识文本，未变更项不计入")
        void changedItem_shouldFormatOnlyItsIdentifier() {
            strategy.compare(
                    snap(rootWithItems(item(new CountingId("A"), "v1"),
                            item(new CountingId("B"), "v1"),
                            item(new CountingId("C"), "v1"))),
                    snap(rootWithItems(item(new CountingId("A"), "v1"),
                            item(new CountingId("B"), "v2"),
                            item(new CountingId("C"), "v1"))));

            assertThat(CountingId.TO_STRING_CALLS).hasValue(1);
        }
    }

    @Nested
    @DisplayName("路径恢复")
    class PathRestoration {

        @Test
        @DisplayName("深层变更后的兄弟字段应保持自身路径")
        void siblingAfterDeepChange_shouldKeepOwnPath() {
            final ObjectNode oldRoot = new ObjectNode(Map.of(
                    "a", new ObjectNode(Map.of("x", new ObjectNode(Map.of("y", new PrimitiveNode("1"))))),
                    "b", new PrimitiveNode("1")));
            final ObjectNode newRoot = new ObjectNode(Map.of(
                    "a", new ObjectNode(Map.of("x", new ObjectNode(Map.of("y", new PrimitiveNode("2"))))),
                    "b", new PrimitiveNode("2")));

            final ChangeNode root = strategy.compare(snap(oldRoot), snap(newRoot));

            assertThat(allPathsOf(root)).contains("a", "a.x", "a.x.y", "b");
        }

        @Test
        @DisplayName("集合项变更后的兄弟字段不应继承集合项路径段")
        void siblingAfterCollectionItemChange_shouldNotInheritItemSegment() {
            final Map<String, ValueNode> oldFields = new LinkedHashMap<>();
            oldFields.put("items", new CollectionNode(List.of(item("A", "v1"))));
            oldFields.put("status", new PrimitiveNode("1"));
            final Map<String, ValueNode> newFields = new LinkedHashMap<>();
            newFields.put("items", new CollectionNode(List.of(item("A", "v2"))));
            newFields.put("status", new PrimitiveNode("2"));
            final ObjectNode oldRoot = new ObjectNode(oldFields);
            final ObjectNode newRoot = new ObjectNode(newFields);

            final ChangeNode root = strategy.compare(snap(oldRoot), snap(newRoot));

            assertThat(childrenOf(root)).extracting(ChangeNode::path).containsExactly("items", "status");
            assertThat(allPathsOf(root)).contains("items[A].value", "status");
        }
    }

    @Nested
    @DisplayName("循环终止（活动节点对状态）")
    class CycleTermination {

        @Test
        @DisplayName("引用同一循环子图的两个兄弟字段都应被比较（活动节点对不泄漏）")
        void twoSiblingsSharingACyclicSubgraph_shouldBothBeCompared() {
            final Map<String, ValueNode> oldSubFields = new HashMap<>();
            final ObjectNode oldSub = new ObjectNode(oldSubFields);
            oldSubFields.put("self", oldSub);
            oldSubFields.put("value", new PrimitiveNode("a"));
            final Map<String, ValueNode> newSubFields = new HashMap<>();
            final ObjectNode newSub = new ObjectNode(newSubFields);
            newSubFields.put("self", newSub);
            newSubFields.put("value", new PrimitiveNode("b"));

            final Map<String, ValueNode> oldRootFields = new LinkedHashMap<>();
            oldRootFields.put("left", oldSub);
            oldRootFields.put("right", oldSub);
            final Map<String, ValueNode> newRootFields = new LinkedHashMap<>();
            newRootFields.put("left", newSub);
            newRootFields.put("right", newSub);
            final ObjectNode oldRoot = new ObjectNode(oldRootFields);
            final ObjectNode newRoot = new ObjectNode(newRootFields);

            final ChangeNode root = strategy.compare(snap(oldRoot), snap(newRoot));

            assertThat(childrenOf(root)).extracting(ChangeNode::path).containsExactly("left", "right");
            assertThat(allPathsOf(root)).contains("left.value", "right.value");
        }
    }

    @Nested
    @DisplayName("边界与既有语义")
    class BoundariesAndLegacySemantics {

        @Test
        @DisplayName("两侧空集合应无变更")
        void emptyCollections_shouldProduceNoChange() {
            final ChangeNode root = strategy.compare(
                    snap(new ObjectNode(Map.of("items", new CollectionNode(List.of())))),
                    snap(new ObjectNode(Map.of("items", new CollectionNode(List.of())))));

            assertThat(childrenOf(root)).isEmpty();
        }

        @Test
        @DisplayName("根为基本值时变更路径应为空字符串")
        void rootPrimitiveChange_shouldReportAtEmptyPath() {
            final ChangeNode root = strategy.compare(
                    snap(new PrimitiveNode("1")),
                    snap(new PrimitiveNode("2")));

            assertThat(childrenOf(root)).containsExactly(new FieldChangeNode("", "1", "2"));
        }

        @Test
        @DisplayName("根为同一实例时应无变更")
        void sameInstanceRoot_shouldProduceNoChange() {
            final ValueNode shared = new ObjectNode(Map.of("value", new PrimitiveNode("x")));

            final ChangeNode root = strategy.compare(snap(shared), snap(shared));

            assertThat(childrenOf(root)).isEmpty();
        }
    }

    @Nested
    @DisplayName("非法输入")
    class InvalidInput {

        @Test
        @DisplayName("null 旧快照应抛 NullPointerException")
        void compare_withNullOldSnapshot_shouldThrowNullPointerException() {
            assertThatThrownBy(() -> strategy.compare(null, snap(new PrimitiveNode("1"))))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("null 新快照应抛 NullPointerException")
        void compare_withNullNewSnapshot_shouldThrowNullPointerException() {
            assertThatThrownBy(() -> strategy.compare(snap(new PrimitiveNode("1")), null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("两种视图的路径与上下文元数据（AC05.3）")
    class FlatViewMetadata {

        @Test
        @DisplayName("集合项字段变更的叶子视图应携带集合上下文元数据")
        void collectionItemLeafChange_shouldCarryCollectionMetadata() {
            final ChangeNode tree = strategy.compare(
                    snap(rootWithItems(item("A", "v1"))),
                    snap(rootWithItems(item("A", "v2"))));
            final ChangeSet changeSet = new ChangeSet(List.of(new ObjectChange(new Object(), tree)));

            final List<Change> leaves = changeSet.getLeafChanges();

            assertThat(leaves).hasSize(1);
            final ValueChange leaf = (ValueChange) leaves.get(0);
            assertThat(leaf.path()).isEqualTo("items[A].value");
            assertThat(leaf.fullPath()).isEqualTo("items[A].value");
            assertThat(leaf.fieldName()).isEqualTo("value");
            assertThat(leaf.collectionFieldName()).isEqualTo("items");
            assertThat(leaf.isParentCollection()).isFalse();
        }

        @Test
        @DisplayName("重复标识删除的叶子视图路径应保留出现序后缀")
        void duplicateRemoval_shouldCarrySuffixInLeafPath() {
            final ChangeNode tree = strategy.compare(
                    snap(rootWithItems(item("A", "v1"), item("A", "v2"))),
                    snap(rootWithItems(item("A", "v1"))));
            final ChangeSet changeSet = new ChangeSet(List.of(new ObjectChange(new Object(), tree)));

            final List<Change> leaves = changeSet.getLeafChanges();

            assertThat(leaves).hasSize(1);
            assertThat(leaves.get(0)).isInstanceOf(ItemRemovedChange.class);
            assertThat(leaves.get(0).path()).isEqualTo("items[A#2]");
            assertThat(leaves.get(0).fullPath()).isEqualTo("items[A#2]");
        }
    }

    /**
     * 创建快照包装。
     *
     * @param node 根节点。
     * @return 快照。
     */
    private static ValueNodeSnapshot snap(final ValueNode node) {
        return new ValueNodeSnapshot(node);
    }

    /**
     * 创建含 items 集合字段的对象根。
     *
     * @param items 集合项。
     * @return 对象根。
     */
    private static ObjectNode rootWithItems(final ValueNode... items) {
        return new ObjectNode(Map.of("items", new CollectionNode(List.of(items))));
    }

    /**
     * 创建以 {@code id} 为业务标识、{@code value} 为载荷的集合项对象节点。
     *
     * @param id    业务标识。
     * @param value 载荷值。
     * @return 集合项对象节点。
     */
    private static ObjectNode item(final Object id, final String value) {
        return new ObjectNode(Map.of("value", new PrimitiveNode(value)), id);
    }

    /**
     * 取根节点的子变更列表。
     *
     * @param root 根变更节点。
     * @return 子变更列表。
     */
    private static List<ChangeNode> childrenOf(final ChangeNode root) {
        return ((ContainerChangeNode) root).children();
    }

    /**
     * 取根节点唯一的 items 容器变更。
     *
     * @param root 根变更节点。
     * @return items 容器变更节点。
     */
    private static ContainerChangeNode itemsChangeOf(final ChangeNode root) {
        return (ContainerChangeNode) childrenOf(root).get(0);
    }

    /**
     * 递归收集变更树中的全部路径。
     *
     * @param node 起始变更节点。
     * @return 全部路径。
     */
    private static List<String> allPathsOf(final ChangeNode node) {
        final List<String> paths = new ArrayList<>();
        collectPaths(node, paths);
        return paths;
    }

    /**
     * 递归收集路径。
     *
     * @param node  当前节点。
     * @param paths 收集列表。
     */
    private static void collectPaths(final ChangeNode node, final List<String> paths) {
        paths.add(node.path());
        if (node instanceof ContainerChangeNode container) {
            for (final ChangeNode child : container.children()) {
                collectPaths(child, paths);
            }
        }
    }

    /**
     * 相等语义基于 value、文本表示计入计数的不可变值类型：用于验证按需格式化（AC05.1）。
     */
    static final class CountingId {

        /**
         * {@code toString} 调用计数。
         */
        static final AtomicInteger TO_STRING_CALLS = new AtomicInteger();

        /**
         * 值。
         */
        private final String value;

        /**
         * 创建标识。
         *
         * @param value 值。
         */
        CountingId(final String value) {
            this.value = value;
        }

        /**
         * 按值比较。
         *
         * @param other 待比较对象。
         * @return 值相同返回 true。
         */
        @Override
        public boolean equals(final Object other) {
            return other instanceof CountingId that && this.value.equals(that.value);
        }

        /**
         * 值哈希。
         *
         * @return 值哈希。
         */
        @Override
        public int hashCode() {
            return this.value.hashCode();
        }

        /**
         * 计数并返回文本。
         *
         * @return 文本表示。
         */
        @Override
        public String toString() {
            TO_STRING_CALLS.incrementAndGet();
            return "CountingId[" + this.value + "]";
        }
    }

    /**
     * 相等语义基于 key、文本表示基于 label 的标识：用于验证等值标识的路径来源。
     */
    static final class DivergentId {

        /**
         * 相等比较键。
         */
        private final String key;

        /**
         * 文本标签。
         */
        private final String label;

        /**
         * 创建标识。
         *
         * @param key   相等比较键。
         * @param label 文本标签。
         */
        DivergentId(final String key, final String label) {
            this.key = key;
            this.label = label;
        }

        /**
         * 按 key 比较。
         *
         * @param other 待比较对象。
         * @return key 相同返回 true。
         */
        @Override
        public boolean equals(final Object other) {
            return other instanceof DivergentId that && this.key.equals(that.key);
        }

        /**
         * key 哈希。
         *
         * @return key 哈希。
         */
        @Override
        public int hashCode() {
            return this.key.hashCode();
        }

        /**
         * 返回文本标签。
         *
         * @return 文本标签。
         */
        @Override
        public String toString() {
            return this.label;
        }
    }
}
