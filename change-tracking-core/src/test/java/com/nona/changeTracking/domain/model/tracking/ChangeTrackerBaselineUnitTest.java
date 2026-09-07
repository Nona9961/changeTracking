package com.nona.changeTracking.domain.model.tracking;

import com.nona.changeTracking.domain.capability.TrackingCapability;
import com.nona.changeTracking.domain.model.changeset.ChangeSet;
import com.nona.changeTracking.domain.model.snapshot.ArrayNode;
import com.nona.changeTracking.domain.model.snapshot.CollectionNode;
import com.nona.changeTracking.domain.model.snapshot.NullNode;
import com.nona.changeTracking.domain.model.snapshot.ObjectNode;
import com.nona.changeTracking.domain.model.snapshot.PrimitiveNode;
import com.nona.changeTracking.domain.model.snapshot.ValueNode;
import com.nona.changeTracking.domain.model.snapshot.ValueNodeSnapshot;
import com.nona.changeTracking.internal.capability.DefaultTrackingCapabilityProvider;
import com.nona.changeTracking.internal.snapshot.ValueNodeDeepCopier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ChangeTracker 基线导出与重建的场景测试（R1 captureBaseline / R2 fromBaseline）。
 * <p>
 * 覆盖三类场景：
 * <ul>
 *   <li>Happy：导出包含已追踪实体、导出物与源 tracker 隔离、重建对已修改实体产出完整
 *       diff（对照：对已修改实体重新 track 得空 diff——反例证明工厂必要性）、往返等价；</li>
 *   <li>Critical：空基线导出/重建、工厂不修改传入基线（可复用两次）；</li>
 *   <li>Fail：循环引用实体导出不栈溢出且循环结构保持、equals 相等的不同实体保持 identity
 *       键语义、深拷贝器对循环/共享结构的保持断言。</li>
 * </ul>
 * <p>
 * 深拷贝结构语义（结构性节点重建、叶子共享、数组复制）以
 * {@link ValueNodeDeepCopier} 的直接用例精确锁定；经 {@code captureBaseline()} 的
 * 端到端用例负责验证公共门面行为。
 * <p>
 * 基线导出/重建契约（{@code captureBaseline()} / {@code fromBaseline(...)}）的
 * 端到端用例：直接锁定库公共门面行为（导出深拷贝、重建不重新脱水、往返可逆）。
 */
@DisplayName("ChangeTracker 基线导出与重建测试")
class ChangeTrackerBaselineUnitTest {

    // ==================== 测试领域模型 ====================

    static class Order {
        private final Long id;
        private final String orderNumber;
        private String status;
        private final List<LineItem> items = new ArrayList<>();

        Order(Long id, String orderNumber) {
            this.id = id;
            this.orderNumber = orderNumber;
            this.status = "PENDING";
        }

        Long getId() {
            return id;
        }

        String getStatus() {
            return status;
        }

        void setStatus(String status) {
            this.status = status;
        }

        List<LineItem> getItems() {
            return items;
        }

        void addItem(LineItem item) {
            this.items.add(item);
        }
    }

    static class LineItem {
        private final Long id;
        private final String sku;
        private int quantity;

        LineItem(Long id, String sku, int quantity) {
            this.id = id;
            this.sku = sku;
            this.quantity = quantity;
        }

        Long getId() {
            return id;
        }

        int getQuantity() {
            return quantity;
        }

        void setQuantity(int quantity) {
            this.quantity = quantity;
        }
    }

    /** 循环引用实体：a.peer = b，b.peer = a。 */
    static class CyclicNode {
        private final String name;
        private CyclicNode peer;

        CyclicNode(String name) {
            this.name = name;
        }
    }

    /** equals 按 id 相等的实体：验证基线的 identity 键语义（equals 相等不合并）。 */
    static class IdEntity {
        private final String id;
        private String value;

        IdEntity(String id, String value) {
            this.id = id;
            this.value = value;
        }

        void setValue(String value) {
            this.value = value;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof IdEntity that && this.id.equals(that.id);
        }

        @Override
        public int hashCode() {
            return this.id.hashCode();
        }
    }

    // ==================== 能力工厂 ====================

    private static TrackingCapability<ValueNodeSnapshot> capability() {
        return new DefaultTrackingCapabilityProvider().create();
    }

    private static TrackingCapability<ValueNodeSnapshot> capabilityWithIdentifiers() {
        // 注意：withIdentifier 的声明返回类型是 SPI 接口 TrackingCapabilityProvider，
        // 链式调用 create() 会解析到接口方法（返回 TrackingCapability<?>）；
        // 须保持接收者静态类型为 DefaultTrackingCapabilityProvider，才能取到
        // TrackingCapability<ValueNodeSnapshot> 的协变返回。
        final DefaultTrackingCapabilityProvider provider = new DefaultTrackingCapabilityProvider();
        provider.withIdentifier(LineItem.class, LineItem::getId);
        return provider.create();
    }

    // ==================== 基线导出（captureBaseline） ====================

    @Nested
    @DisplayName("基线导出（captureBaseline）")
    class CaptureBaseline {

        @Test
        @DisplayName("track 实体后导出基线应包含该实体及其根快照")
        void captureBaseline_afterTrack_shouldContainEntity() {
            final ChangeTracker tracker = new ChangeTracker(capability());
            final Order order = new Order(1L, "ORD-001");
            tracker.track(order);

            final BaselineSnapshot baseline = tracker.captureBaseline();

            assertEquals(1, baseline.entities().size());
            assertTrue(baseline.entities().containsKey(order));
            assertInstanceOf(ObjectNode.class, baseline.entities().get(order));
        }

        @Test
        @DisplayName("重复 captureBaseline 应无副作用，产出内容等价但结构独立的副本")
        void captureBaseline_repeatedCapture_shouldBeDeterministicAndIndependent() {
            final ChangeTracker tracker = new ChangeTracker(capability());
            final Order order = new Order(1L, "ORD-001");
            tracker.track(order);

            final BaselineSnapshot first = tracker.captureBaseline();
            final BaselineSnapshot second = tracker.captureBaseline();

            // 注意：不能直接以 record equals（BaselineSnapshot）断言内容等价——
            // BaselineSnapshot 内部为 IdentityHashMap（identity 键语义），
            // 而 IdentityHashMap.equals 对值按「实例身份」比较（JDK 实现：containsMapping
            // 引用比较 value 槽），非内容语义：两次结构独立的导出（值树必为不同实例）
            // 必然 record-不等。「内容等价」与「结构独立（assertNotSame）」两断言在
            // IdentityHashMap 容器下不可同时成立，故改为显式内容比较（键集合 + 根树内容）。
            assertEquals(first.entities().keySet(), second.entities().keySet(),
                    "重复导出键集合应一致（identity 键语义）");
            final ValueNode firstRoot = first.entities().get(order);
            final ValueNode secondRoot = second.entities().get(order);
            assertNotSame(firstRoot, secondRoot, "两次导出不应共享结构性节点");
            assertEquals(firstRoot, secondRoot, "根快照内容应等价（cycle-safe 内容比较）");
        }

        @Test
        @DisplayName("导出后源 tracker 再追踪新实体不应影响已导出的基线（导出物隔离）")
        void captureBaseline_laterTrack_shouldNotAffectExportedBaseline() {
            final ChangeTracker tracker = new ChangeTracker(capability());
            final Order order = new Order(1L, "ORD-001");
            final Order other = new Order(2L, "ORD-002");
            tracker.track(order);

            final BaselineSnapshot baseline = tracker.captureBaseline();

            tracker.track(other);

            assertEquals(1, baseline.entities().size(), "导出物隔离：源 tracker 变化不应波及基线");
            assertTrue(baseline.entities().containsKey(order));
            assertFalse(baseline.entities().containsKey(other));
        }

        @Test
        @DisplayName("空 tracker 导出基线应返回空映射且不抛异常")
        void captureBaseline_emptyTracker_shouldReturnEmptyBaseline() {
            final ChangeTracker tracker = new ChangeTracker(capability());

            final BaselineSnapshot baseline = tracker.captureBaseline();

            assertTrue(baseline.entities().isEmpty());
        }

        @Test
        @DisplayName("循环引用实体导出不应栈溢出，且循环结构保持（同一源节点复制为同一新实例）")
        void captureBaseline_cyclicEntity_shouldPreserveCycleWithoutStackOverflow() {
            final ChangeTracker tracker = new ChangeTracker(capability());
            final CyclicNode a = new CyclicNode("a");
            final CyclicNode b = new CyclicNode("b");
            a.peer = b;
            b.peer = a;
            tracker.track(a);

            final BaselineSnapshot baseline = tracker.captureBaseline();

            final ObjectNode root = (ObjectNode) baseline.entities().get(a);
            final ObjectNode peer = (ObjectNode) root.field("peer");
            assertSame(root, peer.field("peer"), "循环引用应指向同一个复制实例（结构保持）");
            assertEquals("b", ((PrimitiveNode) peer.field("name")).value());
        }

        @Test
        @DisplayName("equals 相等的不同实体应保持 identity 键语义（与 cleanObjects 一致）")
        void captureBaseline_equalsEqualEntities_shouldKeepIdentityKeys() {
            final ChangeTracker tracker = new ChangeTracker(capability());
            final IdEntity first = new IdEntity("same-id", "v1");
            final IdEntity second = new IdEntity("same-id", "v2");
            tracker.track(first);
            tracker.track(second);

            final BaselineSnapshot baseline = tracker.captureBaseline();

            assertEquals(2, baseline.entities().size(), "identity 键：equals 相等的不同实例不合并");
            assertTrue(baseline.entities().containsKey(first));
            assertTrue(baseline.entities().containsKey(second));
        }
    }

    // ==================== 基线重建（fromBaseline） ====================

    @Nested
    @DisplayName("基线重建（fromBaseline）")
    class FromBaseline {

        @Test
        @DisplayName("对已修改实体应产出完整 diff（对照：重新 track 得空 diff——变更静默丢失）")
        void fromBaseline_shouldProduceFullDiffForModifiedEntity() {
            final TrackingCapability<ValueNodeSnapshot> capability = capabilityWithIdentifiers();
            final Order order = new Order(1L, "ORD-001");
            final LineItem item = new LineItem(100L, "SKU-A", 2);
            order.addItem(item);

            final ChangeTracker original = new ChangeTracker(capability);
            original.track(order);
            final BaselineSnapshot baseline = original.captureBaseline();

            // 业务修改（异步提交侧：基线导出后可自由修改）
            order.setStatus("CONFIRMED");
            item.setQuantity(5);

            final ChangeSet expected = original.calculateChanges();
            assertFalse(expected.isEmpty());

            // 反例：对已修改实体重新 track → 以修改后状态为基线 → 空 diff（变更静默丢失）
            final ChangeTracker naive = new ChangeTracker(capability);
            naive.track(order);
            assertTrue(naive.calculateChanges().isEmpty(), "重新 track 已修改实体应得空 diff——反例对照");

            // 工厂重建：直接登记导出基线（不重新脱水）→ 完整 diff
            final ChangeTracker restored = ChangeTracker.fromBaseline(capability, baseline);
            final ChangeSet restoredChanges = restored.calculateChanges();
            assertFalse(restoredChanges.isEmpty());
            assertEquals(expected, restoredChanges, "重建 tracker 的变更集应与原 tracker 一致");
        }

        @Test
        @DisplayName("空基线重建应产出无基线 tracker，calculateChanges 为空且不抛异常")
        void fromBaseline_emptyBaseline_shouldProduceTrackerWithoutBaseline() {
            final ChangeTracker restored = ChangeTracker.fromBaseline(capability(), new BaselineSnapshot(Map.of()));

            assertTrue(restored.calculateChanges().isEmpty());
        }

        @Test
        @DisplayName("工厂不应修改传入基线（同一基线可复用重建两次，结果一致）")
        void fromBaseline_shouldNotModifyInputBaseline() {
            final TrackingCapability<ValueNodeSnapshot> capability = capabilityWithIdentifiers();
            final Order order = new Order(1L, "ORD-001");
            final ChangeTracker tracker = new ChangeTracker(capability);
            tracker.track(order);
            final BaselineSnapshot baseline = tracker.captureBaseline();
            order.setStatus("CONFIRMED");

            final ChangeTracker restored1 = ChangeTracker.fromBaseline(capability, baseline);
            final ChangeTracker restored2 = ChangeTracker.fromBaseline(capability, baseline);

            assertEquals(1, baseline.entities().size(), "传入基线不应被修改");
            assertEquals(restored1.calculateChanges(), restored2.calculateChanges(), "同一基线重复重建应结果一致");
            assertFalse(restored1.calculateChanges().isEmpty());
        }

        @Test
        @DisplayName("null capability 应抛 NPE（守卫测试）")
        void fromBaseline_nullCapability_shouldThrowNpe() {
            final BaselineSnapshot empty = new BaselineSnapshot(Map.of());

            assertThrows(NullPointerException.class, () -> ChangeTracker.fromBaseline(null, empty));
        }

        @Test
        @DisplayName("null baseline 应抛 NPE（守卫测试）")
        void fromBaseline_nullBaseline_shouldThrowNpe() {
            assertThrows(NullPointerException.class, () -> ChangeTracker.fromBaseline(capability(), null));
        }

        @Test
        @DisplayName("多聚合根全量重建：全部条目登记，多实体变更完整产出")
        void fromBaseline_multipleRoots_shouldRestoreAllEntries() {
            final TrackingCapability<ValueNodeSnapshot> capability = capability();
            final Order orderA = new Order(1L, "ORD-001");
            final Order orderB = new Order(2L, "ORD-002");
            final ChangeTracker tracker = new ChangeTracker(capability);
            tracker.track(orderA);
            tracker.track(orderB);

            final BaselineSnapshot baseline = tracker.captureBaseline();
            assertEquals(2, baseline.entities().size());

            orderA.setStatus("CONFIRMED");
            orderB.setStatus("SHIPPED");

            final ChangeTracker restored = ChangeTracker.fromBaseline(capability, baseline);
            final ChangeSet changes = restored.calculateChanges();

            assertEquals(2, changes.changes().size(), "两实体均应基于导出基线产出各自变更");
        }
    }

    // ==================== 往返等价 ====================

    @Nested
    @DisplayName("往返等价（captureBaseline → fromBaseline → calculateChanges）")
    class RoundTrip {

        @Test
        @DisplayName("往返重建的变更集应与原 tracker 计算结果一致（与 track 基线同语义）")
        void roundTrip_shouldMatchOriginalCalculateChanges() {
            final TrackingCapability<ValueNodeSnapshot> capability = capabilityWithIdentifiers();
            final Order order = new Order(1L, "ORD-001");
            final LineItem item = new LineItem(100L, "SKU-A", 2);
            order.addItem(item);
            final ChangeTracker original = new ChangeTracker(capability);
            original.track(order);

            final BaselineSnapshot baseline = original.captureBaseline();
            order.setStatus("CONFIRMED");
            item.setQuantity(5);

            final ChangeSet expected = original.calculateChanges();
            final ChangeTracker restored = ChangeTracker.fromBaseline(capability, baseline);
            final ChangeSet actual = restored.calculateChanges();

            assertFalse(expected.isEmpty());
            assertEquals(expected, actual, "往返可逆：capture → fromBaseline 计算的变更集应完全等价");
        }
    }

    // ==================== 快照树深拷贝器（ValueNodeDeepCopier） ====================

    @Nested
    @DisplayName("快照树深拷贝器（ValueNodeDeepCopier）")
    class DeepCopier {

        @Test
        @DisplayName("结构复制：结构性节点重建、叶子共享、identifier 原样携带、内容等价")
        void deepCopy_objectAndCollectionTree_shouldRebuildStructureAndShareLeaves() {
            final Object identifier = 1_000_000_000L;
            final PrimitiveNode sharedLeaf = new PrimitiveNode("shared");
            final NullNode nullLeaf = new NullNode();
            final CollectionNode collection = new CollectionNode(new ArrayList<>(List.of(sharedLeaf, nullLeaf)));
            final Map<String, ValueNode> fields = new LinkedHashMap<>();
            final ObjectNode source = new ObjectNode(fields, identifier);
            fields.put("a", sharedLeaf);
            fields.put("b", sharedLeaf);
            fields.put("n", nullLeaf);
            fields.put("coll", collection);

            final ValueNode copyNode = ValueNodeDeepCopier.deepCopy(source);

            assertNotSame(source, copyNode, "结构性节点必须重建");
            final ObjectNode copy = (ObjectNode) copyNode;
            assertEquals(source, copy, "内容应等价（节点内容语义）");
            assertSame(source.identifier(), copy.identifier(), "identifier 原样携带（引用共享）");

            // 叶子共享：同一叶子在副本中仍是同一实例（值不可变，共享安全）
            assertSame(sharedLeaf, copy.field("a"));
            assertSame(sharedLeaf, copy.field("b"));
            assertSame(nullLeaf, copy.field("n"));

            // CollectionNode 重建为全新实例，元素叶子共享
            final ValueNode collCopy = copy.field("coll");
            assertNotSame(collection, collCopy);
            assertInstanceOf(CollectionNode.class, collCopy);
            assertSame(sharedLeaf, ((CollectionNode) collCopy).item(0));
            assertSame(nullLeaf, ((CollectionNode) collCopy).item(1));
        }

        @Test
        @DisplayName("数组复制：数组按组件类型复制新数组（一维复制、多维逐层复制）")
        void deepCopy_arrayNode_shouldCopyArrayByComponentType() {
            final int[] source1d = {1, 2, 3};
            final ArrayNode sourceArray = new ArrayNode(source1d);

            final ArrayNode copy = (ArrayNode) ValueNodeDeepCopier.deepCopy(sourceArray);

            assertNotSame(sourceArray, copy, "ArrayNode 是结构性节点，必须重建");
            assertNotSame(source1d, copy.array(), "数组可变，必须复制新数组");
            assertArrayEquals(source1d, (int[]) copy.array(), "复制后内容等价、组件类型保持");

            final int[][] source2d = {{1, 2}, {3, 4}};
            final ArrayNode copy2d = (ArrayNode) ValueNodeDeepCopier.deepCopy(new ArrayNode(source2d));

            assertNotSame(source2d, copy2d.array(), "多维数组必须整体复制");
            assertNotSame(source2d[0], ((int[][]) copy2d.array())[0], "多维数组内层行也必须复制");
            assertArrayEquals(source2d[0], ((int[][]) copy2d.array())[0]);
            assertArrayEquals(source2d[1], ((int[][]) copy2d.array())[1]);
        }

        @Test
        @DisplayName("循环树复制：不栈溢出且循环结构保持（同一源节点复制为同一新实例）")
        void deepCopy_cyclicTree_shouldPreserveCycleAsSingleInstance() {
            final Map<String, ValueNode> fieldsA = new LinkedHashMap<>();
            final ObjectNode nodeA = new ObjectNode(fieldsA, null);
            final Map<String, ValueNode> fieldsB = new LinkedHashMap<>();
            final ObjectNode nodeB = new ObjectNode(fieldsB, null);
            fieldsA.put("b", nodeB);
            fieldsB.put("a", nodeA);

            final ValueNode copyNode = ValueNodeDeepCopier.deepCopy(nodeA);

            assertNotSame(nodeA, copyNode, "结构性节点必须重建");
            assertEquals(nodeA, copyNode, "循环图内容应等价（cycle-safe 内容比较）");
            final ObjectNode copyA = (ObjectNode) copyNode;
            final ObjectNode copyB = (ObjectNode) copyA.field("b");
            assertNotSame(nodeB, copyB);
            assertSame(copyA, copyB.field("a"), "循环保持：同一源节点 → 同一新实例（identity 缓存）");
        }

        @Test
        @DisplayName("null 根节点应抛 NPE（守卫测试）")
        void deepCopy_nullRoot_shouldThrowNpe() {
            assertThrows(NullPointerException.class, () -> ValueNodeDeepCopier.deepCopy(null));
        }
    }
}