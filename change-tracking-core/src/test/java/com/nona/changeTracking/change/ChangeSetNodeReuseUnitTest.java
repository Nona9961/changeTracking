package com.nona.changeTracking.change;

import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockConstruction;

/**
 * {@link ChangeSet} 视图节点复用单元测试（架构约束的证据面）。
 * <p>
 * 视图契约按值语义解释变化，不承诺跨入口引用身份相等；但「视图遍历复用已建立的结果节点、仅取叶子不建立
 * 完整扁平列表或相对路径副本、重复查询不重建节点」是本次消除投影构造成本的架构约束，须以构造计数与
 * 分配证据验证，而不是只看行为断言。本测试提供两类证据：
 * <ol>
 *   <li><b>构造证据</b>：两个视图返回的元素必须是规范树中已建立节点的<b>同一实例</b>（重建必然产生新实例），
 *       并用 {@code mockConstruction} 直接统计视图调用期间五类变更结果与 {@link ChangeLocation} 的构造次数为 0；</li>
 *   <li><b>分配证据</b>：线程分配字节数对比「仅取叶子」与「完整视图」，在分组远多于叶子的树上，
 *       仅取叶子不得建立完整扁平列表（否则分配量超过完整视图）。</li>
 * </ol>
 */
@DisplayName("ChangeSet 视图节点复用单元测试")
class ChangeSetNodeReuseUnitTest {

    /**
     * 身份断言用的链深度：足够覆盖多层复用又不放大相等性开销。
     */
    private static final int IDENTITY_CHAIN_DEPTH = 8;

    /**
     * 分配对比用的链深度：分组数与叶子数差距显著，便于区分「只收叶子」与「先建完整再过滤」。
     */
    private static final int ALLOCATION_CHAIN_DEPTH = 1024;

    @Nested
    @DisplayName("构造证据")
    class ConstructionEvidence {

        @Test
        @DisplayName("完整视图返回的元素是规范树中同一实例，未重建任何节点")
        void allChanges_shouldReturnTheCanonicalTreeInstances() {
            final ReuseTree tree = ReuseTree.deepChain(IDENTITY_CHAIN_DEPTH);
            final Map<Change, Boolean> canonical = tree.identitySet();

            final List<Change> all = tree.changeSet().getAllChanges();

            assertThat(all).hasSize(tree.nodeCount());
            assertThat(all).allSatisfy(change -> assertThat(canonical).containsKey(change));
            assertThat(all).extracting(Change::fullPath)
                    .containsExactlyElementsOf(tree.preOrderFullPaths());
        }

        @Test
        @DisplayName("叶子视图返回的元素是规范树中叶子的同一实例，未建立相对路径节点副本")
        void leafChanges_shouldReturnTheCanonicalLeafInstance() {
            final ReuseTree tree = ReuseTree.deepChain(IDENTITY_CHAIN_DEPTH);
            final Map<Change, Boolean> canonicalLeaf = new IdentityHashMap<>();
            canonicalLeaf.put(tree.leaf(), Boolean.TRUE);

            final List<Change> leaves = tree.changeSet().getLeafChanges();

            assertThat(leaves).hasSize(1);
            assertThat(canonicalLeaf).containsKey(leaves.get(0));
            assertThat(leaves.get(0)).isSameAs(tree.leaf());
        }

        @Test
        @DisplayName("重复查询不重建节点：两次获取返回逐位同一实例")
        void repeatedAcquisition_shouldReuseTheSameInstances() {
            final ReuseTree tree = ReuseTree.deepChain(IDENTITY_CHAIN_DEPTH);

            final List<Change> firstAll = tree.changeSet().getAllChanges();
            final List<Change> secondAll = tree.changeSet().getAllChanges();
            final List<Change> firstLeaves = tree.changeSet().getLeafChanges();
            final List<Change> secondLeaves = tree.changeSet().getLeafChanges();

            assertThat(secondAll).hasSameSizeAs(firstAll);
            for (int index = 0; index < firstAll.size(); index++) {
                assertThat(secondAll.get(index)).isSameAs(firstAll.get(index));
            }
            for (int index = 0; index < firstLeaves.size(); index++) {
                assertThat(secondLeaves.get(index)).isSameAs(firstLeaves.get(index));
            }
        }

        @Test
        @DisplayName("视图调用期间不构造任何变更结果与定位对象（构造计数为 0）")
        void viewAcquisition_shouldConstructNoResultNodes() {
            final ReuseTree tree = ReuseTree.deepChain(IDENTITY_CHAIN_DEPTH);

            try (MockedConstruction<ContainerChange> containers = mockConstruction(ContainerChange.class);
                 MockedConstruction<ValueChange> values = mockConstruction(ValueChange.class);
                 MockedConstruction<ItemAddedChange> added = mockConstruction(ItemAddedChange.class);
                 MockedConstruction<ItemRemovedChange> removed = mockConstruction(ItemRemovedChange.class);
                 MockedConstruction<ObjectFieldChange> objectFields = mockConstruction(ObjectFieldChange.class);
                 MockedConstruction<ChangeLocation> locations = mockConstruction(ChangeLocation.class)) {
                tree.changeSet().getAllChanges();
                tree.changeSet().getLeafChanges();
                tree.changeSet().getAllChanges();

                assertThat(containers.constructed()).isEmpty();
                assertThat(values.constructed()).isEmpty();
                assertThat(added.constructed()).isEmpty();
                assertThat(removed.constructed()).isEmpty();
                assertThat(objectFields.constructed()).isEmpty();
                assertThat(locations.constructed()).isEmpty();
            }
        }
    }

    @Nested
    @DisplayName("分配证据")
    class AllocationEvidence {

        @Test
        @DisplayName("仅取叶子不建立完整扁平列表：叶子视图的分配量小于完整视图")
        void leafChanges_shouldAllocateLessThanTheFullView() {
            warmUp();

            final ChangeSet forFullView = ReuseTree.deepChain(ALLOCATION_CHAIN_DEPTH).changeSet();
            final long fullViewBytes = allocatedBytes(forFullView::getAllChanges);

            final ChangeSet forLeafView = ReuseTree.deepChain(ALLOCATION_CHAIN_DEPTH).changeSet();
            final long leafViewBytes = allocatedBytes(forLeafView::getLeafChanges);

            assertThat(leafViewBytes).isLessThan(fullViewBytes);
        }

        /**
         * 预热测量路径：让两个视图方法在测量前完成类加载与即时编译。
         */
        private void warmUp() {
            for (int round = 0; round < 8; round++) {
                final ChangeSet warmUpSet = ReuseTree.deepChain(16).changeSet();
                warmUpSet.getAllChanges();
                warmUpSet.getLeafChanges();
            }
        }

        /**
         * 测量当前线程执行给定动作期间分配的字节数。
         *
         * @param action 待测动作
         * @return 分配字节数
         */
        private long allocatedBytes(final Runnable action) {
            final ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
            threads.setThreadAllocatedMemoryEnabled(true);
            final long before = threads.getThreadAllocatedBytes(Thread.currentThread().threadId());
            action.run();
            return threads.getThreadAllocatedBytes(Thread.currentThread().threadId()) - before;
        }
    }

    /**
     * 规范结果树夹具：一条由分组嵌套构成的深链，链末端只有一个叶子。
     */
    private static final class ReuseTree {

        /**
         * 链中的所有规范节点（含叶子），用于身份断言。
         */
        private final List<Change> nodes;

        /**
         * 叶子节点。
         */
        private final Change leaf;

        /**
         * 变更集。
         */
        private final ChangeSet changeSet;

        /**
         * 建立夹具。
         *
         * @param nodes    规范节点列表
         * @param leaf     叶子节点
         * @param changeSet 变更集
         */
        private ReuseTree(final List<Change> nodes, final Change leaf, final ChangeSet changeSet) {
            this.nodes = nodes;
            this.leaf = leaf;
            this.changeSet = changeSet;
        }

        /**
         * 建立指定深度的分组深链：深度 d 产生 d 个分组与 1 个叶子。
         *
         * @param depth 分组层数
         * @return 结果树夹具
         */
        private static ReuseTree deepChain(final int depth) {
            final List<ChangeLocation> levelLocations = new ArrayList<>(depth);
            ChangeLocation current = ChangeLocation.root();
            for (int level = 0; level < depth; level++) {
                current = ChangeLocation.field(current, "level" + level);
                levelLocations.add(current);
            }
            final Change leaf = new ValueChange(ChangeLocation.field(current, "leaf"), "a", "b");
            final List<Change> nodes = new ArrayList<>(depth + 1);
            nodes.add(leaf);
            Change node = leaf;
            for (int level = depth - 1; level >= 0; level--) {
                node = new ContainerChange(levelLocations.get(level), List.of(node));
                nodes.add(node);
            }
            final ChangeSet changeSet = new ChangeSet(List.of(new ObjectChange(new Object(), List.of(node))));
            return new ReuseTree(nodes, leaf, changeSet);
        }

        /**
         * 返回变更集。
         *
         * @return 变更集
         */
        private ChangeSet changeSet() {
            return this.changeSet;
        }

        /**
         * 返回叶子节点。
         *
         * @return 叶子节点
         */
        private Change leaf() {
            return this.leaf;
        }

        /**
         * 返回规范节点数量。
         *
         * @return 节点数量
         */
        private int nodeCount() {
            return this.nodes.size();
        }

        /**
         * 返回规范节点的身份集合。
         *
         * @return 身份集合
         */
        private Map<Change, Boolean> identitySet() {
            final Map<Change, Boolean> identity = new IdentityHashMap<>();
            for (final Change node : this.nodes) {
                identity.put(node, Boolean.TRUE);
            }
            return identity;
        }

        /**
         * 返回完整视图的期望前序完整路径。
         *
         * @return 前序完整路径列表
         */
        private List<String> preOrderFullPaths() {
            final List<String> paths = new ArrayList<>();
            for (final Change node : this.nodes) {
                paths.add(node.fullPath());
            }
            Collections.reverse(paths);
            return paths;
        }
    }
}
