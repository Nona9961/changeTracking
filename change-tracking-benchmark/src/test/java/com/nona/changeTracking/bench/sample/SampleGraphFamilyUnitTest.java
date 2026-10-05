package com.nona.changeTracking.bench.sample;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the frozen graph sample shapes of {@link SampleFamily}: the shared graph
 * keeps two references to the same child per level, the plain tree shares nothing, the cyclic graph
 * closes on its head and the mixed graph carries both, together with the depth, independent object and
 * reference edge counts the benchmark carriers report.
 * <p>
 * The tests read the package visible fields of the sample family, because the graph nodes expose no
 * accessors: the family remains the single construction point and the tests assert its shapes.
 */
@DisplayName("样本族共享与循环图形状单元测试")
class SampleGraphFamilyUnitTest {

    /** Depth used by the shape assertions: small enough to count by hand, deep enough for two levels. */
    private static final int DEPTH = 4;

    @Nested
    @DisplayName("共享图")
    class SharedGraph {

        @Test
        @DisplayName("每层分支的两个引用应指向同一子节点实例")
        void createSharedGraph_shouldReferenceTheSameChildTwice() {
            final SampleGraphNode root = SampleFamily.createSharedGraph(DEPTH);

            assertThat(hasSharedReference(root)).isTrue();
            assertThat(hasCycle(root)).isFalse();
        }

        @Test
        @DisplayName("独立对象数应为深度加一，引用边数应为深度的两倍")
        void createSharedGraph_shouldHoldDepthPlusOneObjectsAndTwoEdgesPerLevel() {
            final SampleGraphNode root = SampleFamily.createSharedGraph(DEPTH);

            assertThat(independentObjectCount(root)).isEqualTo(DEPTH + 1);
            assertThat(referenceEdgeCount(root)).isEqualTo(2 * DEPTH);
        }

        @Test
        @DisplayName("末端应为显式叶子而不是 null 引用")
        void createSharedGraph_shouldEndWithAnExplicitLeaf() {
            SampleGraphNode node = SampleFamily.createSharedGraph(DEPTH);
            while (node instanceof SampleGraphBranch branch) {
                node = branch.first;
            }

            assertThat(node).isInstanceOf(SampleGraphLeaf.class);
        }

        @Test
        @DisplayName("冻结深度应为 16（复现样本每侧 17 个独立对象）")
        void frozenDepth_shouldBeTheReproductionDepth() {
            assertThat(SampleFamily.GRAPH_DEPTH).isEqualTo(16);
            assertThat(independentObjectCount(SampleFamily.createSharedGraph(SampleFamily.GRAPH_DEPTH)))
                    .isEqualTo(SampleFamily.GRAPH_DEPTH + 1);
        }

        @Test
        @DisplayName("非正深度应被拒绝")
        void createSharedGraph_withNonPositiveDepth_shouldThrowIllegalArgumentException() {
            assertThatThrownBy(() -> SampleFamily.createSharedGraph(0))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> SampleFamily.createSharedGraph(-1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("普通树")
    class PlainTree {

        @Test
        @DisplayName("普通树不应共享任何引用，也不应是环")
        void createPlainGraph_shouldShareNothing() {
            final SampleGraphNode root = SampleFamily.createPlainGraph(DEPTH);

            assertThat(hasSharedReference(root)).isFalse();
            assertThat(hasCycle(root)).isFalse();
        }

        @Test
        @DisplayName("独立对象数应为深度的两倍加一，引用边数应为深度的两倍")
        void createPlainGraph_shouldHoldTwoObjectsAndTwoEdgesPerLevel() {
            final SampleGraphNode root = SampleFamily.createPlainGraph(DEPTH);

            assertThat(independentObjectCount(root)).isEqualTo(2 * DEPTH + 1);
            assertThat(referenceEdgeCount(root)).isEqualTo(2 * DEPTH);
        }

        @Test
        @DisplayName("非正深度应被拒绝")
        void createPlainGraph_withNonPositiveDepth_shouldThrowIllegalArgumentException() {
            assertThatThrownBy(() -> SampleFamily.createPlainGraph(0))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("循环图")
    class CyclicGraph {

        @Test
        @DisplayName("循环应闭合回首节点且节点数等于深度")
        void createCyclicGraph_shouldCloseTheCycleOnTheHead() {
            final SampleGraphCycleNode head = (SampleGraphCycleNode) SampleFamily.createCyclicGraph(DEPTH);

            SampleGraphCycleNode current = head;
            for (int index = 0; index < DEPTH; index++) {
                assertThat(current).isNotNull();
                current = current.next;
            }

            assertThat(current).isSameAs(head);
            assertThat(hasCycle(head)).isTrue();
            assertThat(independentObjectCount(head)).isEqualTo(DEPTH);
            assertThat(referenceEdgeCount(head)).isEqualTo(DEPTH);
        }

        @Test
        @DisplayName("非正深度应被拒绝")
        void createCyclicGraph_withNonPositiveDepth_shouldThrowIllegalArgumentException() {
            assertThatThrownBy(() -> SampleFamily.createCyclicGraph(0))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("混合图")
    class MixedGraph {

        @Test
        @DisplayName("混合图应同时携带共享引用与环")
        void createMixedGraph_shouldCarrySharingAndACycle() {
            final SampleGraphNode root = SampleFamily.createMixedGraph(DEPTH);

            assertThat(root).isInstanceOf(SampleGraphBranch.class);
            assertThat(hasSharedReference(root)).isTrue();
            assertThat(hasCycle(root)).isTrue();
        }

        @Test
        @DisplayName("非正深度应被拒绝")
        void createMixedGraph_withNonPositiveDepth_shouldThrowIllegalArgumentException() {
            assertThatThrownBy(() -> SampleFamily.createMixedGraph(0))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("图样本变更入口")
    class GraphMutation {

        @Test
        @DisplayName("变更入口应只改根节点的层值")
        void changeGraphRootValue_shouldChangeOnlyTheRootLayerValue() {
            final SampleGraphNode root = SampleFamily.createSharedGraph(DEPTH);
            final String childValue = layerValueOf(((SampleGraphBranch) root).first);
            final String rootValue = layerValueOf(root);

            SampleMutator.changeGraphRootValue(root);

            assertThat(layerValueOf(root)).isNotEqualTo(rootValue);
            assertThat(layerValueOf(((SampleGraphBranch) root).first)).isEqualTo(childValue);
        }

        @Test
        @DisplayName("重复调用应继续改变根节点层值")
        void changeGraphRootValue_repeated_shouldChangeOnEveryCall() {
            final SampleGraphNode root = SampleFamily.createSharedGraph(DEPTH);

            SampleMutator.changeGraphRootValue(root);
            final String firstChange = layerValueOf(root);
            SampleMutator.changeGraphRootValue(root);

            assertThat(layerValueOf(root)).isNotEqualTo(firstChange);
        }

        @Test
        @DisplayName("null 样本应被拒绝，非图样本应被拒绝")
        void changeGraphRootValue_withInvalidSample_shouldThrow() {
            assertThatThrownBy(() -> SampleMutator.changeGraphRootValue(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> SampleMutator.changeGraphRootValue(new Object()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    /**
     * Returns the layer value of a graph node.
     *
     * @param node the graph node
     * @return the layer value of the node
     * @throws IllegalArgumentException if the node type is not a graph node
     */
    private static String layerValueOf(final SampleGraphNode node) {
        if (node instanceof SampleGraphLeaf leaf) {
            return leaf.layerValue;
        }
        if (node instanceof SampleGraphBranch branch) {
            return branch.layerValue;
        }
        if (node instanceof SampleGraphCycleNode cycle) {
            return cycle.layerValue;
        }
        throw new IllegalArgumentException("Unsupported graph node: " + node.getClass().getName());
    }

    /**
     * Counts the independent objects of a graph (identity deduplicated).
     *
     * @param root the graph root
     * @return the number of independent objects
     */
    private static int independentObjectCount(final SampleGraphNode root) {
        return collectObjects(root, Collections.newSetFromMap(new IdentityHashMap<>())).size();
    }

    /**
     * Collects every graph node reachable from the root (identity deduplicated).
     *
     * @param node    the current node
     * @param visited the visited node set
     * @return the visited node set
     */
    private static Set<SampleGraphNode> collectObjects(final SampleGraphNode node,
                                                       final Set<SampleGraphNode> visited) {
        if (!visited.add(node)) {
            return visited;
        }
        if (node instanceof SampleGraphBranch branch) {
            collectObjects(branch.first, visited);
            collectObjects(branch.second, visited);
        }
        if (node instanceof SampleGraphCycleNode cycle) {
            collectObjects(cycle.next, visited);
        }
        return visited;
    }

    /**
     * Counts the reference edges of a graph (one per slot of every independent node).
     *
     * @param root the graph root
     * @return the number of reference edges
     */
    private static int referenceEdgeCount(final SampleGraphNode root) {
        return countEdges(root, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    /**
     * Recursively counts the reference edges of the graph.
     *
     * @param node    the current node
     * @param visited the visited node set
     * @return the number of reference edges
     */
    private static int countEdges(final SampleGraphNode node, final Set<SampleGraphNode> visited) {
        if (!visited.add(node)) {
            return 0;
        }
        if (node instanceof SampleGraphBranch branch) {
            return 2 + countEdges(branch.first, visited) + countEdges(branch.second, visited);
        }
        if (node instanceof SampleGraphCycleNode cycle) {
            return 1 + countEdges(cycle.next, visited);
        }
        return 0;
    }

    /**
     * Tells whether any branch of the graph references the same child twice.
     *
     * @param root the graph root
     * @return true when a shared reference exists
     */
    private static boolean hasSharedReference(final SampleGraphNode root) {
        return hasSharedReference(root, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    /**
     * Recursively looks for a branch holding the same child twice.
     *
     * @param node    the current node
     * @param visited the visited node set
     * @return true when a shared reference exists
     */
    private static boolean hasSharedReference(final SampleGraphNode node, final Set<SampleGraphNode> visited) {
        if (!visited.add(node)) {
            return false;
        }
        if (node instanceof SampleGraphBranch branch) {
            return branch.first == branch.second
                    || hasSharedReference(branch.first, visited)
                    || hasSharedReference(branch.second, visited);
        }
        if (node instanceof SampleGraphCycleNode cycle) {
            return hasSharedReference(cycle.next, visited);
        }
        return false;
    }

    /**
     * Tells whether the graph contains a cycle.
     *
     * @param root the graph root
     * @return true when a cycle exists
     */
    private static boolean hasCycle(final SampleGraphNode root) {
        return hasCycle(root, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    /**
     * Recursively looks for a cycle on the current traversal path.
     *
     * @param node   the current node
     * @param active the nodes on the current path
     * @return true when a cycle exists
     */
    private static boolean hasCycle(final SampleGraphNode node, final Set<SampleGraphNode> active) {
        if (!active.add(node)) {
            return true;
        }
        try {
            if (node instanceof SampleGraphBranch branch) {
                return hasCycle(branch.first, active) || hasCycle(branch.second, active);
            }
            if (node instanceof SampleGraphCycleNode cycle) {
                return hasCycle(cycle.next, active);
            }
            return false;
        } finally {
            active.remove(node);
        }
    }
}
