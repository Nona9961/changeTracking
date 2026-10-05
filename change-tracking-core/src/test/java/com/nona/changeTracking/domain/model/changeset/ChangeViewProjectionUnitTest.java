package com.nona.changeTracking.domain.model.changeset;

import com.nona.changeTracking.domain.model.snapshot.NullNode;
import com.nona.changeTracking.domain.model.snapshot.ObjectNode;
import com.nona.changeTracking.domain.model.snapshot.PrimitiveNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 变更视图投影核心（包内类）单元测试：唯一转换核心的两条入口、五类输出、上下文规则、前序顺序、
 * 空路径规则与只读契约。
 * <p>
 * 直接断言既有的视图输出契约（`change-model.md` 的双视图、D8 分派表与 `ChangeSet` 既有视图断言），
 * 同时补齐深链构造量、空路径、多位置与两入口上下文差异；每个用例自建前置状态，不依赖其它用例产物。
 */
@DisplayName("ChangeViewProjection 唯一转换核心单元测试")
class ChangeViewProjectionUnitTest {

    /** 被测核心，每个用例重新创建（核心无状态，重复创建不应影响结果）。 */
    private ChangeViewProjection projection;

    @BeforeEach
    void setUp() {
        projection = new ChangeViewProjection();
    }

    /**
     * 用单个对象变更构造投影输入。
     *
     * @param changeTree 变更树根节点
     * @return 只含一个对象变更的输入列表
     */
    private static List<ObjectChange> inputOf(final ChangeNode changeTree) {
        return List.of(new ObjectChange(new Object(), changeTree));
    }

    /**
     * 构造嵌套链：{@code depth} 个容器（{@code "chain"}、{@code "chain.next"} ...）加最深一层的字段叶子。
     * 逻辑节点数为 {@code depth + 1}（容器+叶子）。
     *
     * @param depth 容器层数，至少 1
     * @return 变更树根节点
     */
    private static ChangeNode nestedChain(final int depth) {
        ChangeNode node = new FieldChangeNode("chain" + ".next".repeat(depth - 1) + ".leaf", "old", "new");
        for (int level = depth - 1; level >= 0; level--) {
            node = new ContainerChangeNode("chain" + ".next".repeat(level), List.of(node));
        }
        return new ContainerChangeNode("", List.of(node));
    }

    /**
     * 统计一次视图输出中可达的 Change 实例（含 children 递归）与不同实例数。
     *
     * @param changes 视图输出
     * @return 可达实例数与不同实例数
     */
    private static Reachability reachabilityOf(final List<Change> changes) {
        final List<Change> reachable = new ArrayList<>();
        collectReachable(changes, reachable);
        final var distinct = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Change, Boolean>());
        distinct.addAll(reachable);
        return new Reachability(reachable.size(), distinct.size());
    }

    /**
     * 递归收集一个视图输出中的全部可达实例。
     *
     * @param changes     待收集的列表
     * @param accumulator 收集容器
     */
    private static void collectReachable(final List<Change> changes, final List<Change> accumulator) {
        for (final Change change : changes) {
            accumulator.add(change);
            if (change instanceof ContainerChange container) {
                collectReachable(container.children(), accumulator);
            }
        }
    }

    /**
     * 一次视图输出的可达性统计。
     *
     * @param reachable 可达实例数
     * @param distinct  不同实例数
     */
    private record Reachability(int reachable, int distinct) {
    }

    @Nested
    @DisplayName("五类输出与路径契约")
    class NodeTypesAndPaths {

        @Test
        @DisplayName("完整视图应包含容器与全部叶子，每个逻辑节点恰好一次且保持前序顺序")
        void toAllChanges_shouldHoldEveryNodeOnceInPreOrder() {
            final ChangeNode tree = new ContainerChangeNode("", List.of(
                    new FieldChangeNode("status", "PENDING", "CONFIRMED"),
                    new ContainerChangeNode("items", List.of(
                            new ItemAddedNode("items[100]", new PrimitiveNode("SKU-X")),
                            new FieldChangeNode("items[200].name", "旧名", "新名"))),
                    new ContainerChangeNode("address", List.of(
                            new FieldChangeNode("address.city", "A市", "B市"),
                            new ObjectFieldChangeNode("address.coords", new NullNode(),
                                    new ObjectNode(Map.of("lat", new PrimitiveNode(1))))))));

            final List<Change> allChanges = projection.toAllChanges(inputOf(tree));

            assertThat(allChanges).extracting(Change::path).containsExactly(
                    "status", "items", "items[100]", "items[200].name",
                    "address", "address.city", "address.coords");
            assertThat(allChanges).filteredOn(change -> change instanceof ContainerChange).hasSize(2);
            assertThat(allChanges.stream().map(Change::path).distinct().count()).isEqualTo(allChanges.size());
        }

        @Test
        @DisplayName("完整视图应输出五类 Change：ValueChange / ObjectFieldChange / ContainerChange / ItemAdded / ItemRemoved")
        void toAllChanges_shouldBuildAllFiveChangeTypes() {
            final ChangeNode tree = new ContainerChangeNode("", List.of(
                    new FieldChangeNode("status", "A", "B"),
                    new ObjectFieldChangeNode("coords", new NullNode(), new ObjectNode(Map.of("lat", new PrimitiveNode(1)))),
                    new ContainerChangeNode("address", List.of(new FieldChangeNode("address.city", "A", "B"))),
                    new ItemAddedNode("items[100]", new PrimitiveNode("SKU-X")),
                    new ItemRemovedNode("items[7]", new PrimitiveNode("SKU-7"))));

            final List<Change> allChanges = projection.toAllChanges(inputOf(tree));
            final ContainerChange address = allChanges.stream()
                    .filter(change -> change instanceof ContainerChange)
                    .map(change -> (ContainerChange) change)
                    .findFirst()
                    .orElseThrow();

            // 五类输出均可由唯一核心产出；容器自身的相对表示只出现在父容器 children 中
            assertThat(allChanges).extracting(Change::getClass)
                    .contains(ValueChange.class, ObjectFieldChange.class, ContainerChange.class,
                            ItemAddedChange.class, ItemRemovedChange.class);
            assertThat(address.children()).extracting(Change::getClass).containsExactly(ValueChange.class);
        }

        @Test
        @DisplayName("完整视图的载荷应沿用原节点引用，容器 children 为相对路径且只读")
        void toAllChanges_shouldReuseTheNodePayloads() {
            final ObjectNode newNode = new ObjectNode(Map.of("lat", new PrimitiveNode(1)));
            final PrimitiveNode addedItem = new PrimitiveNode("SKU-X");
            final ChangeNode tree = new ContainerChangeNode("", List.of(
                    new ObjectFieldChangeNode("coords", new NullNode(), newNode),
                    new ContainerChangeNode("items", List.of(new ItemAddedNode("items[100]", addedItem)))));

            final List<Change> allChanges = projection.toAllChanges(inputOf(tree));
            final ObjectFieldChange coords = (ObjectFieldChange) allChanges.get(0);
            final ContainerChange items = (ContainerChange) allChanges.get(1);
            final ItemAddedChange added = (ItemAddedChange) items.children().get(0);

            assertThat(coords.oldNode()).isInstanceOf(NullNode.class);
            assertThat(coords.newNode()).isSameAs(newNode);
            assertThat(added.addedItem()).isSameAs(addedItem);
            assertThat(added.path()).isEqualTo("[100]");
            assertThat(added.fullPath()).isEqualTo("items[100]");
            assertThat(added.fieldName()).isNull();
            assertThat(added.collectionFieldName()).isEqualTo("items");
            assertThat(added.isParentCollection()).isTrue();
            assertThatThrownBy(() -> items.children().add(null))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("叶子视图应只输出叶子，path 与 fullPath 一致，并含 ObjectFieldChange")
        void toLeafChanges_shouldReturnLeavesWithFullPaths() {
            final ChangeNode tree = new ContainerChangeNode("", List.of(
                    new FieldChangeNode("status", "A", "B"),
                    new ContainerChangeNode("address", List.of(
                            new FieldChangeNode("address.city", "A", "B"),
                            new ObjectFieldChangeNode("address.coords", new NullNode(),
                                    new ObjectNode(Map.of("lat", new PrimitiveNode(1))))))));

            final List<Change> leafChanges = projection.toLeafChanges(inputOf(tree));

            assertThat(leafChanges).extracting(Change::path)
                    .containsExactly("status", "address.city", "address.coords");
            assertThat(leafChanges).noneMatch(change -> change instanceof ContainerChange);
            assertThat(leafChanges).allSatisfy(leaf -> assertThat(leaf.path()).isEqualTo(leaf.fullPath()));
            assertThat(leafChanges.get(2)).isInstanceOf(ObjectFieldChange.class);
        }

        @Test
        @DisplayName("空变更集应返回空视图")
        void bothEntries_onEmptyInput_shouldReturnEmptyViews() {
            assertThat(projection.toAllChanges(List.of())).isEmpty();
            assertThat(projection.toLeafChanges(List.of())).isEmpty();
        }

        @Test
        @DisplayName("输入为 null 应被拒绝")
        void bothEntries_withNullInput_shouldThrowNullPointerException() {
            assertThatThrownBy(() -> projection.toAllChanges(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> projection.toLeafChanges(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("上下文元数据")
    class ContextMetadata {

        /** 集合内新增项与集合内字段的变更树：items 下新增 items[100]，并修改 items[200].name。 */
        private final ChangeNode collectionTree = new ContainerChangeNode("", List.of(
                new ContainerChangeNode("items", List.of(
                        new ItemAddedNode("items[100]", new PrimitiveNode("SKU-X")),
                        new FieldChangeNode("items[200].name", "旧名", "新名")))));

        @Test
        @DisplayName("完整视图扁平入口应从空上下文解析：集合项 fieldName=items、collectionFieldName=null")
        void toAllChanges_flatEntry_shouldResolveMetadataFromAnEmptyContext() {
            final List<Change> allChanges = projection.toAllChanges(inputOf(collectionTree));

            final ItemAddedChange added = (ItemAddedChange) allChanges.get(1);
            assertThat(added.path()).isEqualTo("items[100]");
            assertThat(added.fullPath()).isEqualTo("items[100]");
            assertThat(added.fieldName()).isEqualTo("items");
            assertThat(added.collectionFieldName()).isNull();
            assertThat(added.isParentCollection()).isFalse();
        }

        @Test
        @DisplayName("完整视图 children 入口应使用相对路径并继承实际父级上下文")
        void toAllChanges_childrenEntry_shouldCarryRelativePathsAndInheritedContext() {
            final ContainerChange items = (ContainerChange) projection.toAllChanges(inputOf(collectionTree)).get(0);

            final ItemAddedChange added = (ItemAddedChange) items.children().get(0);
            assertThat(added.path()).isEqualTo("[100]");
            assertThat(added.fullPath()).isEqualTo("items[100]");
            assertThat(added.fieldName()).isNull();
            assertThat(added.collectionFieldName()).isEqualTo("items");
            assertThat(added.isParentCollection()).isTrue();
        }

        @Test
        @DisplayName("叶子视图入口继承实际父级上下文：集合项 fieldName=null、collectionFieldName=items、父集合标记=true")
        void toLeafChanges_entry_shouldInheritTheActualParentContext() {
            final List<Change> leafChanges = projection.toLeafChanges(inputOf(collectionTree));

            final ItemAddedChange added = (ItemAddedChange) leafChanges.get(0);
            assertThat(added.path()).isEqualTo("items[100]");
            // 现状：叶子视图的集合项节点以实际父路径为基准解析元数据，相对路径以 "[" 开头，
            // 故 fieldName=null、collectionFieldName=items、isParentCollection=true
            // （与 ChangeSetModelUnitTest.leafChanges_shouldCarryFullPathsAndContextMetadata 一致）
            assertThat(added.fieldName()).isNull();
            assertThat(added.collectionFieldName()).isEqualTo("items");
            assertThat(added.isParentCollection()).isTrue();

            // 集合项内的字段（叶子视图）：path=fullPath，相对父路径以 "[" 开头，故 fieldName=null，
            // 最近集合为 items 且父集合标记为 true
            final ValueChange renamed = (ValueChange) leafChanges.get(1);
            assertThat(renamed.path()).isEqualTo("items[200].name");
            assertThat(renamed.fieldName()).isNull();
            assertThat(renamed.collectionFieldName()).isEqualTo("items");
            assertThat(renamed.isParentCollection()).isTrue();
        }

        @Test
        @DisplayName("两入口对集合项节点的元数据差异是既有契约：同一节点两入口结果不同")
        void twoEntries_shouldDifferOnCollectionItemMetadata() {
            final Change allEntry = projection.toAllChanges(inputOf(collectionTree)).stream()
                    .filter(change -> change instanceof ItemAddedChange)
                    .findFirst()
                    .orElseThrow();
            final Change leafEntry = projection.toLeafChanges(inputOf(collectionTree)).stream()
                    .filter(change -> change instanceof ItemAddedChange)
                    .findFirst()
                    .orElseThrow();

            assertThat(allEntry.fullPath()).isEqualTo(leafEntry.fullPath());
            assertThat(allEntry.path()).isEqualTo(leafEntry.path());
            // 两入口的既有契约差异在集合上下文：完整视图扁平入口从空上下文解析
            // （fieldName=items、collectionFieldName=null、父集合标记=false），
            // 叶子视图的集合项节点以实际父路径为基准（fieldName=null、collectionFieldName=items、父集合标记=true）。
            assertThat(allEntry.fieldName()).isEqualTo("items");
            assertThat(allEntry.collectionFieldName()).isNull();
            assertThat(allEntry.isParentCollection()).isFalse();
            assertThat(leafEntry.fieldName()).isNull();
            assertThat(leafEntry.collectionFieldName()).isEqualTo("items");
            assertThat(leafEntry.isParentCollection()).isTrue();
        }

        @Test
        @DisplayName("主表字段无集合上下文，深层嵌套集合取最近集合字段名")
        void bothEntries_mainTableFieldAndNestedCollection_shouldResolveTheNearestCollection() {
            final ChangeNode tree = new ContainerChangeNode("", List.of(
                    new FieldChangeNode("status", "A", "B"),
                    new ContainerChangeNode("items", List.of(
                            new ContainerChangeNode("items[1].subItems", List.of(
                                    new FieldChangeNode("items[1].subItems[101].value", "A", "B")))))));

            final List<Change> leaves = projection.toLeafChanges(inputOf(tree));

            final ValueChange status = (ValueChange) leaves.get(0);
            assertThat(status.fieldName()).isEqualTo("status");
            assertThat(status.collectionFieldName()).isNull();
            assertThat(status.isParentCollection()).isFalse();

            // 深层嵌套集合内的字段（叶子视图）：相对父路径以 "[" 开头，fieldName=null，
            // 最近集合字段名为 subItems（不含路径前缀），父集合标记为 true
            final ValueChange nested = (ValueChange) leaves.get(1);
            assertThat(nested.path()).isEqualTo("items[1].subItems[101].value");
            assertThat(nested.fieldName()).isNull();
            assertThat(nested.collectionFieldName()).isEqualTo("subItems");
            assertThat(nested.isParentCollection()).isTrue();
        }
    }

    @Nested
    @DisplayName("空路径规则")
    class EmptyPathRules {

        @Test
        @DisplayName("完整视图应跳过空路径节点，扁平列表不含空前缀")
        void toAllChanges_shouldSkipEmptyPathNodes() {
            final ChangeNode tree = new ContainerChangeNode("", List.of(
                    new FieldChangeNode("status", "A", "B"),
                    new ContainerChangeNode("a", List.of(
                            new ContainerChangeNode("", List.of(new FieldChangeNode("a.x", "A", "B")))))));

            final List<Change> allChanges = projection.toAllChanges(inputOf(tree));

            assertThat(allChanges).extracting(Change::path).containsExactly("status", "a", "a.x");
        }

        @Test
        @DisplayName("嵌套空路径节点应保留在父容器 children 中，其子节点仍按前序出现")
        void toAllChanges_nestedEmptyPathNode_shouldStayInChildren() {
            final ChangeNode tree = new ContainerChangeNode("", List.of(
                    new ContainerChangeNode("a", List.of(
                            new ContainerChangeNode("", List.of(new FieldChangeNode("a.x", "A", "B")))))));

            final List<Change> allChanges = projection.toAllChanges(inputOf(tree));
            final ContainerChange a = (ContainerChange) allChanges.get(0);

            // 空路径节点不进扁平列表，但保留在父容器 children 中，且其子节点仍按前序出现在扁平列表里
            assertThat(allChanges).extracting(Change::path).containsExactly("a", "a.x");
            assertThat(a.children()).hasSize(1);
            assertThat(a.children().get(0).path()).isEmpty();
            assertThat(a.children().get(0).fullPath()).isEmpty();
            assertThat(a.children().get(0)).isInstanceOf(ContainerChange.class);
        }

        @Test
        @DisplayName("叶子视图应保留空路径叶子（完整视图跳过它）")
        void toLeafChanges_shouldKeepEmptyPathLeaves() {
            final ChangeNode tree = new ContainerChangeNode("", List.of(
                    new ContainerChangeNode("a", List.of(new FieldChangeNode("", "old", "new")))));

            final List<Change> leaves = projection.toLeafChanges(inputOf(tree));

            assertThat(leaves).hasSize(1);
            assertThat(leaves.get(0).path()).isEmpty();
            assertThat(leaves.get(0).fullPath()).isEmpty();
            assertThat(leaves.get(0)).isInstanceOf(ValueChange.class);
            // 完整视图跳过空路径节点（该节点是全树唯一的非空路径节点之外的叶子）
            assertThat(projection.toAllChanges(inputOf(tree))).extracting(Change::path).containsExactly("a");
        }

        @Test
        @DisplayName("真实策略产出的根级基本值变更（空路径叶子）应在叶子视图出现、完整视图跳过")
        void rootPrimitiveChange_shouldBeALeafOnly() {
            final ChangeNode tree = new com.nona.changeTracking.domain.capability.ValueNodeComparisonStrategy()
                    .compare(new com.nona.changeTracking.domain.model.snapshot.ValueNodeSnapshot(new PrimitiveNode("old")),
                            new com.nona.changeTracking.domain.model.snapshot.ValueNodeSnapshot(new PrimitiveNode("new")));

            assertThat(tree).isInstanceOf(ContainerChangeNode.class);
            assertThat(projection.toAllChanges(inputOf(tree))).isEmpty();
            assertThat(projection.toLeafChanges(inputOf(tree))).extracting(Change::path).containsExactly("");
        }
    }

    @Nested
    @DisplayName("同一节点多位置")
    class MultiPosition {

        @Test
        @DisplayName("同一 ChangeNode 实例出现在两个位置时，两次出现都要输出且各按所在位置解析上下文")
        void sameNodeAtTwoPositions_shouldBeProjectedForEveryOccurrence() {
            final ChangeNode shared = new FieldChangeNode("x.y", "old", "new");
            final ChangeNode tree = new ContainerChangeNode("", List.of(
                    new ContainerChangeNode("a", List.of(shared)),
                    new ContainerChangeNode("b", List.of(shared))));

            final List<Change> allChanges = projection.toAllChanges(inputOf(tree));

            // 两次出现都进扁平列表，各自承载所在位置的上下文；节点自身路径已含字段名，故两处路径相同
            assertThat(allChanges).extracting(Change::path).containsExactly("a", "x.y", "b", "x.y");
            assertThat(allChanges.get(1).fullPath()).isEqualTo("x.y");
            assertThat(allChanges.get(3).fullPath()).isEqualTo("x.y");
            assertThat(allChanges.get(1).fieldName()).isEqualTo("y");
            assertThat(allChanges.get(3).fieldName()).isEqualTo("y");
            assertThat(((ContainerChange) allChanges.get(0)).children()).extracting(Change::path)
                    .containsExactly("x.y");
            assertThat(((ContainerChange) allChanges.get(2)).children()).extracting(Change::path)
                    .containsExactly("x.y");
        }

        @Test
        @DisplayName("同一节点多位置时叶子视图也逐次输出，且不因全局已访问标记丢失")
        void sameNodeAtTwoPositions_leafView_shouldKeepEveryOccurrence() {
            final ChangeNode shared = new FieldChangeNode("x.y", "old", "new");
            final ChangeNode tree = new ContainerChangeNode("", List.of(
                    new ContainerChangeNode("a", List.of(shared)),
                    new ContainerChangeNode("b", List.of(shared))));

            final List<Change> leaves = projection.toLeafChanges(inputOf(tree));

            assertThat(leaves).hasSize(2);
            assertThat(leaves).extracting(Change::fullPath).containsExactly("x.y", "x.y");
            // 值口径（record 相等）只说明两次出现的载荷与元数据一致；实例是否共享不在契约内，
            // 断言不依赖也不排除实例共享。
            assertThat(leaves.get(0)).isEqualTo(leaves.get(1));
            assertThat(leaves.get(0).collectionFieldName()).isEqualTo(leaves.get(1).collectionFieldName());
        }
    }

    @Nested
    @DisplayName("深链构造量与实例共享")
    class DeepChainConstruction {

        /** 冻结深链深度。 */
        private static final int DEEP_CHAIN_DEPTH = 32;

        @Test
        @DisplayName("深链（深度 32）完整视图的 Change 构造数量应为线性，不出现平方增长")
        void toAllChanges_deepChain_shouldConstructLinearly() {
            final List<Change> allChanges = projection.toAllChanges(inputOf(nestedChain(DEEP_CHAIN_DEPTH)));
            final java.util.Set<Change> distinctInstances =
                    java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            distinctInstances.addAll(allChanges);

            // 逻辑节点数 = 32 个容器 + 1 个叶子；每个出现的逻辑节点只做常数次转换，
            // 因此扁平条目 = 逻辑节点数，且按身份口径的不同实例数也等于扁平条目数。
            // 口径说明：此处用身份集合而非 HashSet（值口径）——同一节点出现在多个位置时
            // 值口径会按记录值去重，与身份口径分歧。
            assertThat(allChanges).hasSize(DEEP_CHAIN_DEPTH + 1);
            assertThat(distinctInstances).hasSize(DEEP_CHAIN_DEPTH + 1);
        }

        @Test
        @DisplayName("深链叶子视图不重复构造后代：叶子视图只构造叶子")
        void toLeafChanges_deepChain_shouldNotRebuildDescendants() {
            final Reachability leaves = reachabilityOf(projection.toLeafChanges(inputOf(nestedChain(DEEP_CHAIN_DEPTH))));

            assertThat(leaves.reachable()).isEqualTo(1);
            assertThat(leaves.distinct()).isEqualTo(1);
        }

        @Test
        @DisplayName("深度相邻值（8/32/128）的完整视图实例数应线性增长，不出现平方增长")
        void toAllChanges_neighbouringDepths_shouldGrowLinearly() {
            final int depth8 = distinctOf(8);
            final int depth32 = distinctOf(32);
            final int depth128 = distinctOf(128);

            assertThat(depth32 - depth8).isEqualTo((32 - 8) * 2);
            assertThat(depth128 - depth32).isEqualTo((128 - 32) * 2);
            assertThat(depth8).isEqualTo(2 * (8 + 1) - 1);
            assertThat(depth32).isEqualTo(2 * (32 + 1) - 1);
            assertThat(depth128).isEqualTo(2 * (128 + 1) - 1);
        }

        /**
         * 深链完整视图的不同 Change 实例数。
         *
         * @param depth 容器层数
         * @return 不同实例数
         */
        private int distinctOf(final int depth) {
            return reachabilityOf(projection.toAllChanges(inputOf(nestedChain(depth)))).distinct();
        }
    }

    @Nested
    @DisplayName("重复获取与只读契约")
    class RepeatAndReadOnly {

        /** 一个包含容器与多项叶子的变更树。 */
        private final ChangeNode tree = new ContainerChangeNode("", List.of(
                new ContainerChangeNode("items", List.of(
                        new ItemAddedNode("items[100]", new PrimitiveNode("SKU-X")),
                        new FieldChangeNode("items[200].name", "旧名", "新名"))),
                new FieldChangeNode("status", "A", "B")));

        @Test
        @DisplayName("重复获取完整视图应返回等值快照且不改变值语义")
        void toAllChanges_repeatedAcquisition_shouldReturnEqualViews() {
            final List<Change> first = projection.toAllChanges(inputOf(tree));
            final List<Change> second = projection.toAllChanges(inputOf(tree));

            assertThat(second).isEqualTo(first);
            assertThat(second.stream().map(Change::path).toList())
                    .isEqualTo(first.stream().map(Change::path).toList());
        }

        @Test
        @DisplayName("重复获取叶子视图应返回等值快照且不改变值语义")
        void toLeafChanges_repeatedAcquisition_shouldReturnEqualViews() {
            final List<Change> first = projection.toLeafChanges(inputOf(tree));
            final List<Change> second = projection.toLeafChanges(inputOf(tree));

            assertThat(second).isEqualTo(first);
        }

        @Test
        @DisplayName("两个视图输出均为只读列表，容器 children 同样只读")
        void bothEntries_shouldReturnReadOnlyLists() {
            final List<Change> allChanges = projection.toAllChanges(inputOf(tree));
            final List<Change> leafChanges = projection.toLeafChanges(inputOf(tree));

            assertThatThrownBy(() -> allChanges.add(null)).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> leafChanges.add(null)).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> ((ContainerChange) allChanges.get(0)).children().add(null))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThat(((ContainerChange) allChanges.get(0)).children())
                    .isUnmodifiable()
                    .isSameAs(((ContainerChange) allChanges.get(0)).children());
        }

        @Test
        @DisplayName("同一输入的两次完整视图输出值相等，实例身份不做保证")
        void toAllChanges_shouldNotPromiseInstanceIdentityAcrossCalls() {
            final List<Change> first = projection.toAllChanges(inputOf(tree));
            final List<Change> second = projection.toAllChanges(inputOf(tree));

            assertThat(first).isEqualTo(second);
            assertThat(first).isNotSameAs(second);
        }
    }

    @Nested
    @DisplayName("五类节点的入口接受性")
    class KnownNodeTypes {

        @Test
        @DisplayName("五类合法节点均应被两条入口接受")
        void bothEntries_withValidNodeTypes_shouldNotThrow() {
            final ChangeNode tree = new ContainerChangeNode("", List.of(new FieldChangeNode("status", "A", "B")));

            assertThat(projection.toAllChanges(inputOf(tree))).hasSize(1);
            assertThat(projection.toLeafChanges(inputOf(tree))).hasSize(1);
        }
    }
}
