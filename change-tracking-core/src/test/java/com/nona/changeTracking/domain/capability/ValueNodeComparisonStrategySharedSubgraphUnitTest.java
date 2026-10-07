package com.nona.changeTracking.domain.capability;

import com.nona.changeTracking.domain.model.changeset.ChangeNode;
import com.nona.changeTracking.domain.model.changeset.ContainerChangeNode;
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
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ValueNodeComparisonStrategy} 的共享子图安全复用单元测试。
 * <p>
 * 用手工构造的 {@link ValueNode} 树精确表达共享与循环：每层两个字段引用<b>同一</b>子节点实例得到
 * 共享子图，节点字段回指自身得到循环。旧、新快照侧分别构造，计数叶子为<b>独立且相等</b>的
 * {@link EqualsCountingValue} 实例，因此叶子语义比较的次数可由 {@link EqualsCallCounter} 观察。
 * <p>
 * 覆盖规模增长（随独立节点对与边数增长而非可达路径数）、深度 16 复现样本的 65,536 次
 * 修改前对照与复用后一次实际比较、共享子图有变更时各路径保留、循环与共享混合终止、
 * 截断结论不复用、测试侧计数载体与普通树语义无退化面。每例自建前置状态。
 */
@DisplayName("共享子图安全复用单元测试")
class ValueNodeComparisonStrategySharedSubgraphUnitTest {

    /**
     * 深度 16 复现样本的深度：每侧 17 个独立对象，可达叶子路径 2^16 条。
     */
    private static final int REPRODUCTION_DEPTH = 16;

    /**
     * 复现样本在修改前算法下的叶子比较次数（2^16）。
     */
    private static final long REPRODUCTION_EXPANDED_COMPARISONS = 65_536L;

    /**
     * 共享分支的第一个引用字段名。
     */
    private static final String LEFT_FIELD = "left";

    /**
     * 共享分支的第二个引用字段名，与 {@link #LEFT_FIELD} 在共享样本中引用同一子节点。
     */
    private static final String RIGHT_FIELD = "right";

    /**
     * 逐层独立值字段名。
     */
    private static final String LAYER_VALUE_FIELD = "layerValue";

    /**
     * 末端叶子值字段名。
     */
    private static final String LEAF_VALUE_FIELD = "leafValue";

    /**
     * 末端叶子的值编码。
     */
    private static final String LEAF_CODE = "leaf";

    /**
     * 集合项的值字段名。
     */
    private static final String ITEM_VALUE_FIELD = "itemValue";

    /**
     * 表示「没有任何层被修改」的层次标记。
     */
    private static final int NO_CHANGED_LAYER = -1;

    /**
     * 每例新建的计数与被测策略，避免跨例状态污染。
     */
    private EqualsCallCounter counter;

    /**
     * 每例新建的被测策略实例。
     */
    private ValueNodeComparisonStrategy strategy;

    @BeforeEach
    void setUp() {
        counter = new EqualsCallCounter();
        strategy = new ValueNodeComparisonStrategy();
    }

    @Nested
    @DisplayName("深度 16 复现样本")
    class ReproductionSample {

        @Test
        @DisplayName("复用后单末端值只比较一次，修改前隔离对照为 65,536 次")
        void reproductionSample_shouldCompareTheSingleLeafOnce() {
            final ObjectNode oldChain = sharedChain(REPRODUCTION_DEPTH, false, NO_CHANGED_LAYER, false);
            final ObjectNode newChain = sharedChain(REPRODUCTION_DEPTH, false, NO_CHANGED_LAYER, false);
            final long expanded = expandedComparisons(oldChain, identitySet());

            counter.reset();
            final ChangeNode result = compare(oldChain, newChain);

            assertThat(expanded).isEqualTo(REPRODUCTION_EXPANDED_COMPARISONS);
            assertThat(1L << REPRODUCTION_DEPTH).isEqualTo(REPRODUCTION_EXPANDED_COMPARISONS);
            assertThat(counter.count()).isEqualTo(1L);
            assertThat(leafPaths(result)).isEmpty();
        }

        @Test
        @DisplayName("复现样本每侧应为 17 个独立对象、每层两条引用边")
        void reproductionSample_shouldHoldSeventeenIndependentObjects() {
            final ObjectNode chain = sharedChain(REPRODUCTION_DEPTH, false, NO_CHANGED_LAYER, false);

            assertThat(independentObjectCount(chain)).isEqualTo(17);
            assertThat(referenceEdgeCount(chain)).isEqualTo(2 * REPRODUCTION_DEPTH);
        }

        @Test
        @DisplayName("两次 compare 调用之间不复用比较结论")
        void twoCompareCalls_shouldNotShareConclusions() {
            final ObjectNode oldChain = sharedChain(4, false, NO_CHANGED_LAYER, false);
            final ObjectNode newChain = sharedChain(4, false, NO_CHANGED_LAYER, false);

            counter.reset();
            compare(oldChain, newChain);
            final long firstCall = counter.count();
            counter.reset();
            compare(oldChain, newChain);
            final long secondCall = counter.count();

            assertThat(firstCall).isEqualTo(1L);
            assertThat(secondCall).isEqualTo(1L);
        }
    }

    @Nested
    @DisplayName("规模样本")
    class ScaleSample {

        @Test
        @DisplayName("逐层带独立值的共享样本应记录深度、节点对数、边数与叶子比较次数")
        void scaleSample_shouldRecordDepthNodePairsEdgesAndLeafComparisons() {
            final List<String> report = new ArrayList<>();

            for (final int depth : List.of(2, 4, 8, 16)) {
                final ObjectNode oldChain = sharedChain(depth, true, NO_CHANGED_LAYER, false);
                final ObjectNode newChain = sharedChain(depth, true, NO_CHANGED_LAYER, false);
                final long expanded = expandedComparisons(oldChain, identitySet());
                final int nodePairs = depth + 1;
                final int edges = 2 * depth;

                counter.reset();
                final ChangeNode result = compare(oldChain, newChain);

                assertThat(leafPaths(result)).as("paths at depth %d", depth).isEmpty();
                assertThat(independentObjectCount(oldChain)).as("node pairs at depth %d", depth).isEqualTo(nodePairs);
                assertThat(referenceEdgeCount(oldChain)).as("edges at depth %d", depth).isEqualTo(edges);
                assertThat(counter.count()).as("reused leaf comparisons at depth %d", depth).isEqualTo(nodePairs);
                assertThat(expanded).as("expanded leaf comparisons at depth %d", depth)
                        .isEqualTo((1L << (depth + 1)) - 1);
                report.add("depth=" + depth + ",nodePairs=" + nodePairs + ",edges=" + edges
                        + ",reusedLeafComparisons=" + counter.count() + ",expandedLeafComparisons=" + expanded);
            }

            assertThat(report).containsExactly(
                    "depth=2,nodePairs=3,edges=4,reusedLeafComparisons=3,expandedLeafComparisons=7",
                    "depth=4,nodePairs=5,edges=8,reusedLeafComparisons=5,expandedLeafComparisons=31",
                    "depth=8,nodePairs=9,edges=16,reusedLeafComparisons=9,expandedLeafComparisons=511",
                    "depth=16,nodePairs=17,edges=32,reusedLeafComparisons=17,expandedLeafComparisons=131071");
        }

        @Test
        @DisplayName("比较工作随独立节点对线性增长，不再随可达路径数指数增长")
        void scaleSample_shouldGrowWithNodePairsInsteadOfPaths() {
            final ObjectNode shallow = sharedChain(2, true, NO_CHANGED_LAYER, false);
            final ObjectNode deep = sharedChain(8, true, NO_CHANGED_LAYER, false);

            final long shallowReused = reusedComparisons(shallow, sharedChain(2, true, NO_CHANGED_LAYER, false));
            final long deepReused = reusedComparisons(deep, sharedChain(8, true, NO_CHANGED_LAYER, false));

            assertThat(shallowReused).isEqualTo(3L);
            assertThat(deepReused).isEqualTo(9L);
            assertThat(deepReused - shallowReused).isEqualTo(6L);
            assertThat(expandedComparisons(deep, identitySet())).isGreaterThan(deepReused * 20L);
        }
    }

    @Nested
    @DisplayName("含集合的共享子图")
    class CollectionSharing {

        @Test
        @DisplayName("含集合的无变更共享子图应整体复用其无变更结论")
        void unchangedSharedSubgraphWithCollection_shouldReuseTheWholeConclusion() {
            final ObjectNode oldRoot = sharedCollectionGraph(2, false);
            final ObjectNode newRoot = sharedCollectionGraph(2, false);
            final long expanded = expandedComparisons(oldRoot, identitySet());

            counter.reset();
            final ChangeNode result = compare(oldRoot, newRoot);

            assertThat(expanded).isEqualTo(4L);
            assertThat(counter.count()).isEqualTo(2L);
            assertThat(leafPaths(result)).isEmpty();
        }

        @Test
        @DisplayName("含集合的共享子图有变更时各路径均保留")
        void changedSharedSubgraphWithCollection_shouldKeepEveryPath() {
            final ObjectNode oldRoot = sharedCollectionGraph(2, false);
            final ObjectNode newRoot = sharedCollectionGraph(2, true);

            counter.reset();
            final ChangeNode result = compare(oldRoot, newRoot);

            assertThat(leafPaths(result)).containsExactly(
                    "a.items[item-0]." + ITEM_VALUE_FIELD,
                    "b.items[item-0]." + ITEM_VALUE_FIELD);
        }
    }

    @Nested
    @DisplayName("有变更的共享子图")
    class ChangedSubgraph {

        @Test
        @DisplayName("末端叶子变更时各条应报告路径均保留")
        void changedLeaf_shouldKeepEveryPath() {
            final ObjectNode oldChain = sharedChain(2, true, NO_CHANGED_LAYER, false);
            final ObjectNode newChain = sharedChain(2, true, NO_CHANGED_LAYER, true);

            counter.reset();
            final ChangeNode result = compare(oldChain, newChain);

            assertThat(leafPaths(result)).containsExactlyInAnyOrder(
                    "left.left." + LEAF_VALUE_FIELD,
                    "left.right." + LEAF_VALUE_FIELD,
                    "right.left." + LEAF_VALUE_FIELD,
                    "right.right." + LEAF_VALUE_FIELD);
        }

        @Test
        @DisplayName("中间层值变更时只有可达该层的路径报告变更")
        void changedLayer_shouldReportOnlyThePathsReachingIt() {
            final ObjectNode unchanged = sharedChain(2, true, NO_CHANGED_LAYER, false);

            counter.reset();
            final ChangeNode rootLevelResult = compare(unchanged, sharedChain(2, true, 0, false));

            assertThat(leafPaths(rootLevelResult)).containsExactly(LAYER_VALUE_FIELD);

            counter.reset();
            final ChangeNode innerLevelResult = compare(unchanged, sharedChain(2, true, 1, false));

            assertThat(leafPaths(innerLevelResult)).containsExactlyInAnyOrder(
                    LEFT_FIELD + "." + LAYER_VALUE_FIELD,
                    RIGHT_FIELD + "." + LAYER_VALUE_FIELD);
        }

        @Test
        @DisplayName("同一 compare 内已处理但有变化的节点对不得被跳过")
        void changedNodePair_shouldNotBeSkippedAfterBeingProcessed() {
            final ObjectNode oldChain = sharedChain(1, true, NO_CHANGED_LAYER, false);
            final ObjectNode newChain = sharedChain(1, true, NO_CHANGED_LAYER, true);

            counter.reset();
            final ChangeNode result = compare(oldChain, newChain);

            assertThat(leafPaths(result)).containsExactlyInAnyOrder(
                    LEFT_FIELD + "." + LEAF_VALUE_FIELD,
                    RIGHT_FIELD + "." + LEAF_VALUE_FIELD);
        }
    }

    @Nested
    @DisplayName("循环截断与结论安全条件")
    class CycleSafety {

        @Test
        @DisplayName("依赖循环截断的空结论不得被另一条路径复用")
        void truncatedConclusion_shouldNotBeReusedByAnotherPath() {
            final ObjectNode oldRoot = cyclicRoot(2, "C");
            final ObjectNode newRoot = cyclicRoot(2, "C");

            counter.reset();
            final ChangeNode result = compare(oldRoot, newRoot);

            assertThat(counter.count()).isEqualTo(2L);
            assertThat(leafPaths(result)).isEmpty();
        }

        @Test
        @DisplayName("循环与共享混合图应终止且只复用真正完成的无变更子图")
        void cyclicAndSharedMixedGraph_shouldTerminateAndReuseTheSharedPart() {
            final ObjectNode oldRoot = mixedRoot();
            final ObjectNode newRoot = mixedRoot();

            counter.reset();
            final ChangeNode result = compare(oldRoot, newRoot);

            assertThat(counter.count()).isEqualTo(2L);
            assertThat(leafPaths(result)).isEmpty();
        }

        @Test
        @DisplayName("循环内的值变更应按既有语义报告")
        void changeInsideACycle_shouldBeReported() {
            final ObjectNode oldRoot = singleFieldRoot("cycle", cyclicNode("C", false));
            final ObjectNode newRoot = singleFieldRoot("cycle", cyclicNode("C", true));

            counter.reset();
            final ChangeNode result = compare(oldRoot, newRoot);

            assertThat(leafPaths(result)).containsExactly("cycle.value");
        }

        @Test
        @DisplayName("同一节点实例出现在两侧时应短路且不产生变更")
        void identicalNodeInstance_shouldShortCircuitWithoutComparison() {
            final ObjectNode chain = sharedChain(2, true, NO_CHANGED_LAYER, false);

            counter.reset();
            final ChangeNode result = compare(chain, chain);

            assertThat(counter.count()).isZero();
            assertThat(leafPaths(result)).isEmpty();
        }
    }

    @Nested
    @DisplayName("无共享普通树的语义面")
    class PlainTree {

        @Test
        @DisplayName("无共享普通树的比较次数与修改前模型一致，不产生虚假复用")
        void plainTree_shouldCompareEveryIndependentNode() {
            final ObjectNode oldTree = plainTree(false);
            final ObjectNode newTree = plainTree(false);
            final long expanded = expandedComparisons(oldTree, identitySet());

            counter.reset();
            final ChangeNode result = compare(oldTree, newTree);

            assertThat(expanded).isEqualTo(3L);
            assertThat(counter.count()).isEqualTo(3L);
            assertThat(leafPaths(result)).isEmpty();
        }

        @Test
        @DisplayName("内容相等但实例不同的子图是两个独立子问题，各自比较")
        void contentEqualButDistinctSubgraphs_shouldBothBeCompared() {
            final ObjectNode oldTree = plainTree(false);
            final ObjectNode newTree = plainTree(false);

            counter.reset();
            compare(oldTree, newTree);

            assertThat(counter.count()).isEqualTo(independentObjectCount(oldTree));
        }

        @Test
        @DisplayName("普通树的叶子变更应逐条报告")
        void plainTreeChanges_shouldBeReportedOncePerLeaf() {
            final ObjectNode oldTree = plainTree(false);
            final ObjectNode newTree = plainTree(true);

            counter.reset();
            final ChangeNode result = compare(oldTree, newTree);

            assertThat(leafPaths(result)).containsExactlyInAnyOrder(
                    LAYER_VALUE_FIELD,
                    LEFT_FIELD + "." + LEAF_VALUE_FIELD,
                    RIGHT_FIELD + "." + LEAF_VALUE_FIELD);
        }
    }

    @Nested
    @DisplayName("依赖失败与异常退出")
    class Failures {

        @Test
        @DisplayName("值比较抛出时应原样传播，不吞异常")
        void valueComparisonFailure_shouldPropagateTheOriginalFailure() {
            final RuntimeException failure = new IllegalStateException("value comparison failed");
            final ObjectNode oldRoot = singleFieldRoot("value", new PrimitiveNode(new ThrowingValue(failure)));
            final ObjectNode newRoot = singleFieldRoot("value", new PrimitiveNode(new ThrowingValue(failure)));

            assertThatThrownBy(() -> compare(oldRoot, newRoot))
                    .isSameAs(failure);
        }

        @Test
        @DisplayName("失败后同一策略实例的下一次比较仍从全新会话开始并复用其自身结论")
        void compareAfterAFailure_shouldStartANewSession() {
            final RuntimeException failure = new IllegalStateException("value comparison failed");
            final ObjectNode oldFailingRoot = singleFieldRoot("value", new PrimitiveNode(new ThrowingValue(failure)));
            final ObjectNode newFailingRoot = singleFieldRoot("value", new PrimitiveNode(new ThrowingValue(failure)));
            assertThatThrownBy(() -> compare(oldFailingRoot, newFailingRoot))
                    .isSameAs(failure);

            final ObjectNode oldChain = sharedChain(4, false, NO_CHANGED_LAYER, false);
            final ObjectNode newChain = sharedChain(4, false, NO_CHANGED_LAYER, false);
            counter.reset();
            final ChangeNode result = compare(oldChain, newChain);

            assertThat(counter.count()).isEqualTo(1L);
            assertThat(leafPaths(result)).isEmpty();
        }
    }

    /**
     * 构造共享链：{@code depth} 层分支，每层 {@code left}/{@code right} 字段引用同一子节点实例，
     * 末端为一个叶子；{@code perLayerValue} 为 true 时每层带一个独立的计数叶子值，
     * {@code changedLayer} 指定被修改的层次（{@link #NO_CHANGED_LAYER} 表示都不修改），
     * {@code changeLeaf} 表示末端叶子值是否被修改。
     *
     * @param depth        分支层数，至少 1。
     * @param perLayerValue 每层是否携带独立计数叶子值。
     * @param changedLayer 被修改的层次下标，{@link #NO_CHANGED_LAYER} 表示无。
     * @param changeLeaf   末端叶子值是否修改。
     * @return 链根节点，深度为 {@code depth + 1} 的独立对象。
     */
    private ObjectNode sharedChain(final int depth, final boolean perLayerValue, final int changedLayer,
                                   final boolean changeLeaf) {
        ValueNode node = leaf(LEAF_CODE + (changeLeaf ? "-changed" : ""));
        for (int level = depth - 1; level >= 0; level--) {
            final Map<String, ValueNode> fields = new LinkedHashMap<>();
            if (perLayerValue) {
                fields.put(LAYER_VALUE_FIELD, countingValue("L" + level + (level == changedLayer ? "-changed" : "")));
            }
            fields.put(LEFT_FIELD, node);
            fields.put(RIGHT_FIELD, node);
            node = new ObjectNode(fields);
        }
        return (ObjectNode) node;
    }

    /**
     * 构造只带一个计数叶子值的叶子对象。
     *
     * @param code 叶子值编码。
     * @return 叶子对象节点。
     */
    private ObjectNode leaf(final String code) {
        final Map<String, ValueNode> fields = new LinkedHashMap<>();
        fields.put(LEAF_VALUE_FIELD, countingValue(code));
        return new ObjectNode(fields);
    }

    /**
     * 构造含集合的共享子图：根的两个字段引用<b>同一</b>子图实例；子图带一个集合字段，
     * 集合项为带业务标识的复杂对象（标识用于集合匹配，不参与计数）。
     *
     * @param itemCount      集合项数量。
     * @param changeFirstItem 是否修改第一项的值。
     * @return 共享集合子图的根节点。
     */
    private ObjectNode sharedCollectionGraph(final int itemCount, final boolean changeFirstItem) {
        final List<ValueNode> items = new ArrayList<>(itemCount);
        for (int index = 0; index < itemCount; index++) {
            final Map<String, ValueNode> itemFields = new LinkedHashMap<>();
            itemFields.put(ITEM_VALUE_FIELD,
                    countingValue("I" + index + (changeFirstItem && index == 0 ? "-changed" : "")));
            items.add(new ObjectNode(itemFields, "item-" + index));
        }
        final Map<String, ValueNode> subgraphFields = new LinkedHashMap<>();
        subgraphFields.put("items", new CollectionNode(items));
        final ObjectNode subgraph = new ObjectNode(subgraphFields);
        final Map<String, ValueNode> rootFields = new LinkedHashMap<>();
        rootFields.put("a", subgraph);
        rootFields.put("b", subgraph);
        return new ObjectNode(rootFields);
    }

    /**
     * 构造一个自引用（循环）对象节点的根：根的两个字段引用同一循环节点实例。
     *
     * @param fieldCount  根引用的字段数量，1 或 2。
     * @param code        循环节点的值编码。
     * @return 循环节点的根。
     */
    private ObjectNode cyclicRoot(final int fieldCount, final String code) {
        final ObjectNode cyclic = cyclicNode(code, false);
        final Map<String, ValueNode> fields = new LinkedHashMap<>();
        fields.put("a", cyclic);
        if (fieldCount > 1) {
            fields.put("b", cyclic);
        }
        return new ObjectNode(fields);
    }

    /**
     * 构造循环与共享混合图：一个字段指向自引用循环节点，另一个字段指向共享子图。
     *
     * @return 混合图根节点。
     */
    private ObjectNode mixedRoot() {
        final ObjectNode cyclic = cyclicNode("C", false);
        final ObjectNode sharedBranch = sharedChain(1, false, NO_CHANGED_LAYER, false);
        final Map<String, ValueNode> fields = new LinkedHashMap<>();
        fields.put("cyclic", cyclic);
        fields.put("shared", sharedBranch);
        return new ObjectNode(fields);
    }

    /**
     * 构造自引用循环节点：{@code value} 承载计数叶子，{@code next} 回指自身。
     *
     * @param code        值编码。
     * @param changeValue 新侧是否承载被修改的值编码。
     * @return 自引用循环节点。
     */
    private ObjectNode cyclicNode(final String code, final boolean changeValue) {
        final Map<String, ValueNode> fields = new LinkedHashMap<>();
        final ObjectNode node = new ObjectNode(fields);
        fields.put("value", countingValue(code + (changeValue ? "-changed" : "")));
        fields.put("next", node);
        return node;
    }

    /**
     * 构造只带一个字段的根对象。
     *
     * @param fieldName 字段名。
     * @param fieldNode 字段节点。
     * @return 单字段根节点。
     */
    private ObjectNode singleFieldRoot(final String fieldName, final ValueNode fieldNode) {
        final Map<String, ValueNode> fields = new LinkedHashMap<>();
        fields.put(fieldName, fieldNode);
        return new ObjectNode(fields);
    }

    /**
     * 构造无共享的普通树：根带逐层值，两个字段指向内容相等但实例不同的两个叶子。
     *
     * @param changeAll 是否修改根值与两个叶子的值。
     * @return 普通树根节点。
     */
    private ObjectNode plainTree(final boolean changeAll) {
        final String suffix = changeAll ? "-changed" : "";
        final Map<String, ValueNode> fields = new LinkedHashMap<>();
        fields.put(LAYER_VALUE_FIELD, countingValue("root" + suffix));
        fields.put(LEFT_FIELD, leaf("leftLeaf" + suffix));
        fields.put(RIGHT_FIELD, leaf("rightLeaf" + suffix));
        return new ObjectNode(fields);
    }

    /**
     * 构造一个把 {@code equals} 调用计入外置计数器的计数叶子。
     *
     * @param code 值编码。
     * @return 承载计数叶子的 PrimitiveNode。
     */
    private PrimitiveNode countingValue(final String code) {
        return new PrimitiveNode(new EqualsCountingValue(code, counter));
    }

    /**
     * 比较两个手工构造的快照。
     *
     * @param oldRoot 旧侧根节点。
     * @param newRoot 新侧根节点。
     * @return 变更树根节点。
     */
    private ChangeNode compare(final ValueNode oldRoot, final ValueNode newRoot) {
        return strategy.compare(new ValueNodeSnapshot(oldRoot), new ValueNodeSnapshot(newRoot));
    }

    /**
     * 在清零计数后比较两个样本，返回实际发生的叶子比较次数。
     *
     * @param oldRoot 旧侧根节点。
     * @param newRoot 新侧根节点。
     * @return 本次比较的 {@code equals} 调用次数。
     */
    private long reusedComparisons(final ValueNode oldRoot, final ValueNode newRoot) {
        counter.reset();
        compare(oldRoot, newRoot);
        return counter.count();
    }

    /**
     * 复现修改前算法的叶子比较次数模型：按引用展开所有可达路径，每个基本值/空值叶子比较一次；
     * 当前递归路径上再次出现的容器节点按既有规则终止，不再展开。
     *
     * @param node   当前节点。
     * @param active 当前递归路径上的容器节点（身份比较）。
     * @return 展开后的叶子比较次数。
     */
    private static long expandedComparisons(final ValueNode node, final Set<ValueNode> active) {
        if (node instanceof PrimitiveNode || node instanceof NullNode) {
            return 1L;
        }
        if (!(node instanceof ObjectNode || node instanceof CollectionNode) || !active.add(node)) {
            return 0L;
        }
        try {
            final AtomicLong comparisons = new AtomicLong();
            if (node instanceof ObjectNode objectNode) {
                objectNode.forEachField((name, fieldValue) ->
                        comparisons.addAndGet(expandedComparisons(fieldValue, active)));
            }
            if (node instanceof CollectionNode collectionNode) {
                collectionNode.forEachItem(item -> comparisons.addAndGet(expandedComparisons(item, active)));
            }
            return comparisons.get();
        } finally {
            active.remove(node);
        }
    }

    /**
     * 统计独立复杂对象节点数（按引用身份去重，仅计容器节点）。
     *
     * @param root 根节点。
     * @return 独立对象数。
     */
    private static int independentObjectCount(final ValueNode root) {
        final Set<ValueNode> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        collectObjects(root, visited);
        return visited.size();
    }

    /**
     * 递归收集容器节点（身份去重）。
     *
     * @param node    当前节点。
     * @param visited 已收集节点集。
     */
    private static void collectObjects(final ValueNode node, final Set<ValueNode> visited) {
        if (!(node instanceof ObjectNode || node instanceof CollectionNode) || !visited.add(node)) {
            return;
        }
        if (node instanceof ObjectNode objectNode) {
            objectNode.forEachField((name, fieldValue) -> collectObjects(fieldValue, visited));
        }
        if (node instanceof CollectionNode collectionNode) {
            collectionNode.forEachItem(item -> collectObjects(item, visited));
        }
    }

    /**
     * 统计指向子图的引用边数：每个独立容器节点上指向复杂对象或集合的字段与集合项各计一条；
     * 指向基本值或空值的槽位不计（它们是叶子语义比较的载荷，不是图的边）。
     *
     * @param root 根节点。
     * @return 引用边数量。
     */
    private static int referenceEdgeCount(final ValueNode root) {
        return countEdges(root, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    /**
     * 递归统计指向子图的引用边。
     *
     * @param node    当前节点。
     * @param visited 已访问容器节点集。
     * @return 引用边数量。
     */
    private static int countEdges(final ValueNode node, final Set<ValueNode> visited) {
        if (!isGraphNode(node) || !visited.add(node)) {
            return 0;
        }
        final AtomicLong edges = new AtomicLong();
        if (node instanceof ObjectNode objectNode) {
            objectNode.forEachField((name, fieldValue) -> countEdge(fieldValue, visited, edges));
        }
        if (node instanceof CollectionNode collectionNode) {
            collectionNode.forEachItem(item -> countEdge(item, visited, edges));
        }
        return (int) edges.get();
    }

    /**
     * 把一个字段或集合项槽位计入引用边（指向子图时），并递归其子图。
     *
     * @param slot    槽位节点。
     * @param visited 已访问容器节点集。
     * @param edges   边数累计。
     */
    private static void countEdge(final ValueNode slot, final Set<ValueNode> visited, final AtomicLong edges) {
        if (!isGraphNode(slot)) {
            return;
        }
        edges.incrementAndGet();
        edges.addAndGet(countEdges(slot, visited));
    }

    /**
     * 判断节点是否为图结构节点（复杂对象或集合）。
     *
     * @param node 待判断节点。
     * @return 图结构节点返回 true。
     */
    private static boolean isGraphNode(final ValueNode node) {
        return node instanceof ObjectNode || node instanceof CollectionNode;
    }

    /**
     * 按前序展开变更树，收集全部叶子变更的路径。
     *
     * @param node 变更树根节点。
     * @return 叶子路径列表，按展开顺序。
     */
    private static List<String> leafPaths(final ChangeNode node) {
        final List<String> paths = new ArrayList<>();
        collectPaths(node, paths);
        return paths;
    }

    /**
     * 递归收集叶子路径。
     *
     * @param node  当前变更节点。
     * @param paths 收集目标。
     */
    private static void collectPaths(final ChangeNode node, final List<String> paths) {
        if (node instanceof ContainerChangeNode container) {
            for (final ChangeNode child : container.children()) {
                collectPaths(child, paths);
            }
            return;
        }
        paths.add(node.path());
    }

    /**
     * 创建一个按引用身份比较的节点集。
     *
     * @return 身份集合。
     */
    private static Set<ValueNode> identitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    /**
     * 在 {@code equals} 中抛出固定异常的探针值：用于验证依赖失败的原样传播。
     */
    static final class ThrowingValue {

        /**
         * 每次 {@code equals} 抛出的异常实例。
         */
        private final RuntimeException failure;

        /**
         * 创建探针值。
         *
         * @param failure 待抛出的异常实例。
         */
        ThrowingValue(final RuntimeException failure) {
            this.failure = failure;
        }

        /**
         * 直接抛出固定异常。
         *
         * @param other 待比较对象。
         * @return 不返回，始终抛出。
         */
        @Override
        public boolean equals(final Object other) {
            throw this.failure;
        }

        /**
         * 固定哈希，保证异常由 {@code equals} 触发而非哈希。
         *
         * @return 固定 0。
         */
        @Override
        public int hashCode() {
            return 0;
        }
    }
}
