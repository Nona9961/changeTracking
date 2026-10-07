package com.nona.changeTracking.bench.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link RetainedGraph}: the reachable set, the retained byte total and the per class
 * aggregation of hand built object graphs with known object counts and known byte totals.
 * <p>
 * Every graph is built inside its own test, so no case depends on the state another case left behind.
 * The expected figures are the estimator values for the reachable objects; sharing, cycles, null
 * slots and a repeated root have to be counted once each, so the cases lock the identity based walk
 * rather than a naive recursion.
 */
@DisplayName("RetainedGraph 保留可达集测量单元测试")
class RetainedGraphUnitTest {

    /** Leaf carrier with a single int field: 16 bytes shallow. */
    static final class Leaf {

        /** Payload. */
        int value;
    }

    /** Branch carrier holding two leaf references: 24 bytes shallow. */
    static final class Branch {

        /** Left child. */
        Leaf left;

        /** Right child. */
        Leaf right;
    }

    /** Cycle carrier holding a self reference and an int field: 24 bytes shallow. */
    static final class CycleNode {

        /** Next node of the cycle. */
        CycleNode next;

        /** Payload. */
        int value;
    }

    /** Carrier holding a primitive array and a reference array: 24 bytes shallow. */
    static final class ArrayHolder {

        /** Primitive array. */
        int[] numbers;

        /** Reference array. */
        Object[] refs;
    }

    /** Carrier holding a string field. */
    static final class Labeled {

        /** String payload. */
        String label;
    }

    @Test
    @DisplayName("无共享的树应逐对象计数")
    void measure_withPlainTree_shouldCountEveryObject() {
        final Leaf first = new Leaf();
        final Leaf second = new Leaf();
        final Branch root = new Branch();
        root.left = first;
        root.right = second;

        final RetainedFootprint footprint = RetainedGraph.measure(root);

        assertThat(footprint.objectCount()).isEqualTo(3);
        assertThat(footprint.retainedBytes()).isEqualTo(56L);
        assertThat(footprint.classFootprints()).hasSize(2);
        assertThat(footprint.classFootprints().get(0).className()).isEqualTo(Leaf.class.getName());
        assertThat(footprint.classFootprints().get(0).objectCount()).isEqualTo(2);
        assertThat(footprint.classFootprints().get(0).bytes()).isEqualTo(32L);
        assertThat(footprint.classFootprints().get(1).className()).isEqualTo(Branch.class.getName());
        assertThat(footprint.classFootprints().get(1).objectCount()).isEqualTo(1);
        assertThat(footprint.classFootprints().get(1).bytes()).isEqualTo(24L);
    }

    @Test
    @DisplayName("共享子图应只计一次")
    void measure_withSharedSubgraph_shouldCountTheSharedObjectOnce() {
        final Leaf shared = new Leaf();
        final Branch root = new Branch();
        root.left = shared;
        root.right = shared;

        final RetainedFootprint footprint = RetainedGraph.measure(root);

        assertThat(footprint.objectCount()).isEqualTo(2);
        assertThat(footprint.retainedBytes()).isEqualTo(40L);
    }

    @Test
    @DisplayName("自环应终止并只计一个对象")
    void measure_withSelfCycle_shouldTerminateAndCountOnce() {
        final CycleNode node = new CycleNode();
        node.next = node;

        final RetainedFootprint footprint = RetainedGraph.measure(node);

        assertThat(footprint.objectCount()).isEqualTo(1);
        assertThat(footprint.retainedBytes()).isEqualTo(24L);
    }

    @Test
    @DisplayName("两节点环应终止并计两个对象")
    void measure_withTwoNodeCycle_shouldCountBothNodes() {
        final CycleNode first = new CycleNode();
        final CycleNode second = new CycleNode();
        first.next = second;
        second.next = first;

        final RetainedFootprint footprint = RetainedGraph.measure(first);

        assertThat(footprint.objectCount()).isEqualTo(2);
        assertThat(footprint.retainedBytes()).isEqualTo(48L);
    }

    @Test
    @DisplayName("数组应连同元素一起被遍历")
    void measure_withArrays_shouldTraverseElements() {
        final Leaf leaf = new Leaf();
        final ArrayHolder holder = new ArrayHolder();
        holder.numbers = new int[]{1, 2, 3, 4};
        holder.refs = new Object[]{leaf};

        final RetainedFootprint footprint = RetainedGraph.measure(holder);

        assertThat(footprint.objectCount()).isEqualTo(4);
        assertThat(footprint.retainedBytes()).isEqualTo(96L);
    }

    @Test
    @DisplayName("数组空槽与空字段不应被计数")
    void measure_withNullSlots_shouldSkipNulls() {
        final Leaf leaf = new Leaf();
        final ArrayHolder holder = new ArrayHolder();
        holder.numbers = new int[0];
        holder.refs = new Object[]{null, leaf, null};

        final RetainedFootprint footprint = RetainedGraph.measure(holder);

        assertThat(footprint.objectCount()).isEqualTo(4);
        assertThat(footprint.retainedBytes()).isEqualTo(88L);
    }

    @Test
    @DisplayName("字符串载荷应连同其后备字节数组一起被遍历")
    void measure_withStringPayload_shouldTraverseBackingArray() {
        final Labeled labeled = new Labeled();
        labeled.label = "ab";

        final RetainedFootprint footprint = RetainedGraph.measure(labeled);

        assertThat(footprint.objectCount()).isEqualTo(3);
        assertThat(footprint.retainedBytes()).isEqualTo(64L);
    }

    @Test
    @DisplayName("字符串字面量作为根时应计字符串与其后备数组")
    void measure_withStringRoot_shouldCountStringAndBackingArray() {
        final RetainedFootprint footprint = RetainedGraph.measure("ab");

        assertThat(footprint.objectCount()).isEqualTo(2);
        assertThat(footprint.retainedBytes()).isEqualTo(48L);
    }

    @Test
    @DisplayName("字符串尺寸应以后备字节数组长度为准，而非字符个数")
    void measure_withMultiByteString_shouldSizeByBackingByteLength() {
        final RetainedFootprint twoChars = RetainedGraph.measure("\u4e2d\u6587");
        final RetainedFootprint fourAsciiChars = RetainedGraph.measure("abcd");

        assertThat(twoChars.objectCount()).isEqualTo(2);
        assertThat(fourAsciiChars.objectCount()).isEqualTo(2);
        assertThat(twoChars.retainedBytes()).isEqualTo(48L);
        assertThat(fourAsciiChars.retainedBytes()).isEqualTo(48L);
    }

    @Test
    @DisplayName("多个根的重叠可达集应只计一次")
    void measure_withOverlappingRoots_shouldCountTheUnionOnce() {
        final Leaf first = new Leaf();
        final Leaf second = new Leaf();
        final Branch root = new Branch();
        root.left = first;
        root.right = second;

        final RetainedFootprint footprint = RetainedGraph.measure(root, first);

        assertThat(footprint.objectCount()).isEqualTo(3);
        assertThat(footprint.retainedBytes()).isEqualTo(56L);
    }

    @Test
    @DisplayName("空根集合与空根应产生空脚印")
    void measure_withNoReachableObject_shouldReportEmptyFootprint() {
        final RetainedFootprint noRoots = RetainedGraph.measure();
        final RetainedFootprint nullRoot = RetainedGraph.measure((Object) null);

        assertThat(noRoots.objectCount()).isZero();
        assertThat(noRoots.retainedBytes()).isZero();
        assertThat(noRoots.classFootprints()).isEmpty();
        assertThat(nullRoot.objectCount()).isZero();
        assertThat(nullRoot.retainedBytes()).isZero();
    }

    @Test
    @DisplayName("null 根数组应被拒绝")
    void measure_withNullRootArray_shouldBeRejected() {
        assertThatThrownBy(() -> RetainedGraph.measure((Object[]) null))
                .isInstanceOf(NullPointerException.class);
    }
}
