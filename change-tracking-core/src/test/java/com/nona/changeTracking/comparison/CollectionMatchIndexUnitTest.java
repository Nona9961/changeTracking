package com.nona.changeTracking.comparison;

import com.nona.changeTracking.snapshot.CollectionNode;
import com.nona.changeTracking.snapshot.NullNode;
import com.nona.changeTracking.snapshot.ObjectNode;
import com.nona.changeTracking.snapshot.PrimitiveNode;
import com.nona.changeTracking.snapshot.ValueNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CollectionMatchIndex} 单元测试：有序标识匹配、重复标识、null 项、位置标识、标识来源与边界。
 */
@DisplayName("CollectionMatchIndex 有序标识匹配单元测试")
class CollectionMatchIndexUnitTest {

    @Nested
    @DisplayName("有序匹配与标识来源")
    class OrderedMatching {

        @Test
        @DisplayName("唯一标识两侧各匹配一次，保持旧侧首次出现顺序")
        void uniqueIdentifiers_shouldMatchEachGroupOnceInOldOrder() {
            final CollectionMatchIndex index = CollectionMatchIndex.of(
                    coll(item(2L, "a"), item(1L, "b")),
                    coll(item(1L, "b"), item(2L, "a")));

            final List<CollectionMatchIndex.MatchGroup> groups = groupsOf(index);

            assertThat(groups).hasSize(2);
            assertThat(groups.get(0).identity()).isEqualTo(2L);
            assertThat(groups.get(1).identity()).isEqualTo(1L);
            assertThat(groups).allSatisfy(group -> {
                assertThat(group.oldCount()).isEqualTo(1);
                assertThat(group.newCount()).isEqualTo(1);
            });
        }

        @Test
        @DisplayName("新独有标识应追加在旧标识之后")
        void newExclusiveIdentifier_shouldAppendAfterOldIdentifiers() {
            final CollectionMatchIndex index = CollectionMatchIndex.of(
                    coll(item(1L, "a"), item(2L, "b")),
                    coll(item(1L, "a"), item(3L, "c")));

            final List<CollectionMatchIndex.MatchGroup> groups = groupsOf(index);

            assertThat(groups).extracting(CollectionMatchIndex.MatchGroup::identity)
                    .containsExactly(1L, 2L, 3L);
            final CollectionMatchIndex.MatchGroup newOnly = groups.get(2);
            assertThat(newOnly.oldCount()).isZero();
            assertThat(newOnly.newCount()).isEqualTo(1);
            final CollectionMatchIndex.MatchGroup removed = groups.get(1);
            assertThat(removed.oldCount()).isEqualTo(1);
            assertThat(removed.newCount()).isZero();
        }

        @Test
        @DisplayName("重复标识应合并为一组并保留各侧出现次序")
        void duplicateIdentifiers_shouldBeOneGroupKeepingAppearanceOrder() {
            final CollectionMatchIndex index = CollectionMatchIndex.of(
                    coll(item(7L, "a"), item(7L, "b")),
                    coll(item(7L, "c")));

            final List<CollectionMatchIndex.MatchGroup> groups = groupsOf(index);

            assertThat(groups).hasSize(1);
            final CollectionMatchIndex.MatchGroup group = groups.get(0);
            assertThat(group.identity()).isEqualTo(7L);
            assertThat(group.oldCount()).isEqualTo(2);
            assertThat(group.newCount()).isEqualTo(1);
            assertThat(valueOf(group.oldItem(0))).isEqualTo("a");
            assertThat(valueOf(group.oldItem(1))).isEqualTo("b");
            assertThat(valueOf(group.newItem(0))).isEqualTo("c");
        }

        @Test
        @DisplayName("null 标识项应形成自己的匹配组")
        void nullIdentifier_shouldFormItsOwnGroup() {
            final CollectionMatchIndex index = CollectionMatchIndex.of(
                    new CollectionNode(List.of(new NullNode())),
                    new CollectionNode(List.of(new NullNode())));

            final List<CollectionMatchIndex.MatchGroup> groups = groupsOf(index);

            assertThat(groups).hasSize(1);
            assertThat(groups.get(0).identity()).isNull();
            assertThat(groups.get(0).oldCount()).isEqualTo(1);
            assertThat(groups.get(0).newCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("标识相等但实例不同时，组的路径来源应为旧侧首次出现的实例")
        void equalIdentitiesOfDifferentInstances_shouldKeepOldSideIdentityAsSource() {
            final DivergentId oldIdentity = new DivergentId("A", "old-label");
            final DivergentId newIdentity = new DivergentId("A", "new-label");
            final CollectionMatchIndex index = CollectionMatchIndex.of(
                    coll(item(oldIdentity, "v1")),
                    coll(item(newIdentity, "v2")));

            final List<CollectionMatchIndex.MatchGroup> groups = groupsOf(index);

            assertThat(groups).hasSize(1);
            assertThat(groups.get(0).identity()).isSameAs(oldIdentity);
            assertThat(String.valueOf(groups.get(0).identity())).isEqualTo("old-label");
        }

        @Test
        @DisplayName("无业务标识的项应按位置匹配（pos:n）")
        void nonBusinessIdentifierItems_shouldMatchByPosition() {
            final CollectionMatchIndex index = CollectionMatchIndex.of(
                    new CollectionNode(List.of(nested("x"))),
                    new CollectionNode(List.of(nested("y"))));

            final List<CollectionMatchIndex.MatchGroup> groups = groupsOf(index);

            assertThat(groups).hasSize(1);
            assertThat(String.valueOf(groups.get(0).identity())).isEqualTo("pos:0");
            assertThat(groups.get(0).oldCount()).isEqualTo(1);
            assertThat(groups.get(0).newCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("多个无业务标识的项应按位置形成不同分组")
        void multiplePositionalItems_shouldFormDistinctGroups() {
            final CollectionMatchIndex index = CollectionMatchIndex.of(
                    new CollectionNode(List.of(nested("x"), nested("y"))),
                    new CollectionNode(List.of()));

            final List<CollectionMatchIndex.MatchGroup> groups = groupsOf(index);

            assertThat(groups).extracting(group -> String.valueOf(group.identity()))
                    .containsExactly("pos:0", "pos:1");
        }

        @Test
        @DisplayName("基本值项应按值匹配")
        void primitiveItems_shouldMatchByValue() {
            final CollectionMatchIndex index = CollectionMatchIndex.of(
                    new CollectionNode(List.of(new PrimitiveNode("A"))),
                    new CollectionNode(List.of(new PrimitiveNode("A"))));

            final List<CollectionMatchIndex.MatchGroup> groups = groupsOf(index);

            assertThat(groups).hasSize(1);
            assertThat(groups.get(0).identity()).isEqualTo("A");
        }

        @Test
        @DisplayName("重排不应改变分组数量与各侧计数")
        void reorder_shouldNotChangeGroupCounts() {
            final CollectionMatchIndex index = CollectionMatchIndex.of(
                    coll(item(1L, "a"), item(2L, "b"), item(3L, "c")),
                    coll(item(3L, "c"), item(2L, "b"), item(1L, "a")));

            final List<CollectionMatchIndex.MatchGroup> groups = groupsOf(index);

            assertThat(groups).extracting(CollectionMatchIndex.MatchGroup::identity)
                    .containsExactly(1L, 2L, 3L);
            assertThat(groups).allSatisfy(group -> {
                assertThat(group.oldCount()).isEqualTo(1);
                assertThat(group.newCount()).isEqualTo(1);
            });
        }
    }

    @Nested
    @DisplayName("空集合边界")
    class EmptyCollections {

        @Test
        @DisplayName("旧侧为空时全部为新独有组")
        void emptyOld_shouldProduceNewOnlyGroups() {
            final CollectionMatchIndex index = CollectionMatchIndex.of(
                    new CollectionNode(List.of()),
                    coll(item(1L, "a"), item(2L, "b")));

            final List<CollectionMatchIndex.MatchGroup> groups = groupsOf(index);

            assertThat(groups).hasSize(2);
            assertThat(groups).allSatisfy(group -> {
                assertThat(group.oldCount()).isZero();
                assertThat(group.newCount()).isEqualTo(1);
            });
        }

        @Test
        @DisplayName("新侧为空时全部为旧独有组")
        void emptyNew_shouldProduceOldOnlyGroups() {
            final CollectionMatchIndex index = CollectionMatchIndex.of(
                    coll(item(1L, "a"), item(2L, "b")),
                    new CollectionNode(List.of()));

            final List<CollectionMatchIndex.MatchGroup> groups = groupsOf(index);

            assertThat(groups).hasSize(2);
            assertThat(groups).allSatisfy(group -> {
                assertThat(group.oldCount()).isEqualTo(1);
                assertThat(group.newCount()).isZero();
            });
        }

        @Test
        @DisplayName("两侧为空时不产生任何分组")
        void bothEmpty_shouldProduceNoGroups() {
            final CollectionMatchIndex index = CollectionMatchIndex.of(
                    new CollectionNode(List.of()),
                    new CollectionNode(List.of()));

            assertThat(groupsOf(index)).isEmpty();
        }
    }

    @Nested
    @DisplayName("非法输入与越界")
    class InvalidInputAndBounds {

        @Test
        @DisplayName("null 旧集合应被拒绝")
        void of_withNullOld_shouldThrowNullPointerException() {
            assertThatThrownBy(() -> CollectionMatchIndex.of(null, new CollectionNode(List.of())))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("null 新集合应被拒绝")
        void of_withNullNew_shouldThrowNullPointerException() {
            assertThatThrownBy(() -> CollectionMatchIndex.of(new CollectionNode(List.of()), null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("访问不存在的侧项下标应抛 IndexOutOfBoundsException")
        void itemAccess_outOfRange_shouldThrowIndexOutOfBoundsException() {
            final CollectionMatchIndex index = CollectionMatchIndex.of(
                    new CollectionNode(List.of()),
                    coll(item(1L, "a")));
            final CollectionMatchIndex.MatchGroup newOnly = groupsOf(index).get(0);

            assertThatThrownBy(() -> newOnly.oldItem(0))
                    .isInstanceOf(IndexOutOfBoundsException.class);
            assertThatThrownBy(() -> newOnly.newItem(1))
                    .isInstanceOf(IndexOutOfBoundsException.class);
        }
    }

    /**
     * 构造集合节点。
     *
     * @param items 集合项。
     * @return 集合节点。
     */
    private static CollectionNode coll(final ValueNode... items) {
        return new CollectionNode(List.of(items));
    }

    /**
     * 构造带业务标识的集合项对象节点。
     *
     * @param identifier 业务标识。
     * @param value      载荷值。
     * @return 集合项对象节点。
     */
    private static ObjectNode item(final Object identifier, final String value) {
        return new ObjectNode(Map.of("id", new PrimitiveNode(identifier), "value", new PrimitiveNode(value)), identifier);
    }

    /**
     * 构造无业务标识的嵌套集合项节点。
     *
     * @param value 载荷值。
     * @return 嵌套集合节点。
     */
    private static CollectionNode nested(final String value) {
        return new CollectionNode(List.of(new PrimitiveNode(value)));
    }

    /**
     * 读取集合项对象的 value 字段。
     *
     * @param node 集合项对象节点。
     * @return value 字段的字符串值。
     */
    private static String valueOf(final ValueNode node) {
        return (String) ((PrimitiveNode) ((ObjectNode) node).field("value")).value();
    }

    /**
     * 按首次出现顺序取出全部匹配项组。
     *
     * @param index 匹配索引。
     * @return 匹配项组列表。
     */
    private static List<CollectionMatchIndex.MatchGroup> groupsOf(final CollectionMatchIndex index) {
        final List<CollectionMatchIndex.MatchGroup> groups = new ArrayList<>();
        index.forEachGroup(groups::add);
        return groups;
    }

    /**
     * 相等语义基于 key、文本表示基于 label 的标识：用于验证等值标识的路径来源。
     */
    private static final class DivergentId {

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
