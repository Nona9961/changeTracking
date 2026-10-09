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
 * rather than a naive recursion. The aggregation cases lock the order of tied class totals, and the
 * class literal case locks that a {@code Class} leaf is counted without being expanded.
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

    /**
     * Carrier holding a class literal field: 16 bytes shallow. Its {@code Class} value takes part in
     * the measurement as a leaf.
     */
    static final class Typed {

        /** Class literal payload. */
        Class<?> type;
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
        // Both payloads have five characters, but the Latin1 payload is backed by byte[5] (24 aligned)
        // while the UTF-16 payload is backed by byte[10] (32 aligned), so the pair distinguishes the
        // backing byte length from String.length(): sizing by the character count would report 48B for
        // both payloads.
        final RetainedFootprint fiveLatin1Chars = RetainedGraph.measure("abcde");
        final RetainedFootprint fiveUtf16Chars = RetainedGraph.measure("\u4e2d\u6587\u5b57ab");

        assertThat(fiveLatin1Chars.objectCount()).isEqualTo(2);
        assertThat(fiveUtf16Chars.objectCount()).isEqualTo(2);
        assertThat(fiveLatin1Chars.retainedBytes()).isEqualTo(48L);
        assertThat(fiveUtf16Chars.retainedBytes()).isEqualTo(56L);
    }

    @Test
    @DisplayName("Class 字段应作为叶子计入尺寸但不展开")
    void measure_withClassLeaf_shouldCountItsSizeWithoutTraversingIt() {
        final Typed typed = new Typed();
        typed.type = Leaf.class;

        final RetainedFootprint footprint = RetainedGraph.measure(typed);

        // The class literal is counted alone: 16 bytes for the carrier plus the 72 byte Class
        // estimate of ObjectLayout (12 byte object header plus the declared fields, aligned to 8
        // bytes). Several declared reference fields are non null for a class literal (for example its
        // name), so a traversed leaf would raise the object count and the byte totals instead of
        // counting the class literal alone.
        assertThat(footprint.objectCount()).isEqualTo(2);
        assertThat(footprint.retainedBytes()).isEqualTo(88L);
        assertThat(footprint.classFootprints()).extracting(ClassFootprint::className)
                .containsExactly(Class.class.getName(), Typed.class.getName());
        assertThat(footprint.classFootprints()).extracting(ClassFootprint::objectCount)
                .containsExactly(1, 1);
        assertThat(footprint.classFootprints()).extracting(ClassFootprint::bytes)
                .containsExactly(72L, 16L);
    }

    @Test
    @DisplayName("类聚合字节数并列时应按对象个数降序、再按类名升序排列")
    void measure_withTiedClassTotals_shouldOrderByDescendingCountThenClassName() {
        // Leaf: 16 bytes each, three objects -> 48; Branch: 24 bytes each, two objects -> 48.
        final RetainedFootprint tiedCounts = RetainedGraph.measure(
                new Leaf(), new Leaf(), new Leaf(), new Branch(), new Branch());
        final RetainedFootprint tiedNames = RetainedGraph.measure(new Branch(), new CycleNode());

        assertThat(tiedCounts.retainedBytes()).isEqualTo(96L);
        assertThat(tiedCounts.objectCount()).isEqualTo(5);
        assertThat(tiedCounts.classFootprints()).extracting(ClassFootprint::className)
                .containsExactly(Leaf.class.getName(), Branch.class.getName());
        assertThat(tiedCounts.classFootprints()).extracting(ClassFootprint::objectCount)
                .containsExactly(3, 2);
        assertThat(tiedCounts.classFootprints()).extracting(ClassFootprint::bytes)
                .containsExactly(48L, 48L);

        // Branch: 24 bytes, one object; CycleNode: 24 bytes, one object -> the binary class name
        // decides, so the smaller name leads.
        assertThat(tiedNames.classFootprints()).extracting(ClassFootprint::className)
                .containsExactly(Branch.class.getName(), CycleNode.class.getName());
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
