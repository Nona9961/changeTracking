package com.nona.changeTracking.domain.model.tracking;

import com.nona.changeTracking.domain.capability.TrackingCapability;
import com.nona.changeTracking.domain.model.changeset.ChangeSet;
import com.nona.changeTracking.domain.model.changeset.ObjectChange;
import com.nona.changeTracking.domain.model.snapshot.ValueNodeSnapshot;
import com.nona.changeTracking.internal.capability.DefaultTrackingCapabilityProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.IdentityHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ChangeTracker 单实体变更计算（calculateChangesFor）的场景测试。
 * <p>
 * 锁定 002 定案契约：
 * <ul>
 *   <li>有变更 → 单元素 ChangeSet（target 为该实体，identity 键语义）；</li>
 *   <li>未追踪（含 stopTracking）/ 已追踪无变更 → 空 ChangeSet，不抛异常；</li>
 *   <li>与全局 {@code calculateChanges()} 按 target 过滤等价（同一比较链路）；</li>
 *   <li>幂等视图：不更新基线，重复调用结果一致。</li>
 * </ul>
 */
@DisplayName("ChangeTracker 单实体变更计算测试")
class ChangeTrackerCalculateChangesForTest {

    // ==================== 测试领域模型 ====================

    static class Order {
        private final Long id;
        private final String orderNumber;
        private String status;

        Order(Long id, String orderNumber) {
            this.id = id;
            this.orderNumber = orderNumber;
            this.status = "PENDING";
        }

        Long getId() {
            return id;
        }

        String getOrderNumber() {
            return orderNumber;
        }

        String getStatus() {
            return status;
        }

        void setStatus(String status) {
            this.status = status;
        }
    }

    /** equals 按 id 相等的实体：验证 identity 键语义（结合 IdentityHashMap）。 */
    static class IdEntity {
        private final String id;
        private String status;

        IdEntity(String id, String status) {
            this.id = id;
            this.status = status;
        }

        void setStatus(String status) {
            this.status = status;
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

    // ==================== 辅助 ====================

    /**
     * 按 target（identity 键语义）索引变更，消除 IdentityHashMap 迭代顺序的影响。
     */
    private static Map<Object, ObjectChange> indexByTarget(final ChangeSet changeSet) {
        final Map<Object, ObjectChange> byTarget = new IdentityHashMap<>();
        for (final ObjectChange objectChange : changeSet.changes()) {
            byTarget.put(objectChange.target(), objectChange);
        }
        return byTarget;
    }

    // ==================== Happy Path ====================

    @Nested
    @DisplayName("单实体变更计算（Happy Path）")
    class PerEntityCalculation {

        @Test
        @DisplayName("3 实体全变更：calculateChangesFor(u1) 返回单元素 ChangeSet，target 与树同全局一致")
        void threeRootsAllChanged_forOneRoot_shouldReturnSingleObjectChange() {
            final ChangeTracker tracker = new ChangeTracker(capability());
            final Order rootA = new Order(1L, "ORD-A");
            final Order rootB = new Order(2L, "ORD-B");
            final Order rootC = new Order(3L, "ORD-C");
            tracker.track(rootA);
            tracker.track(rootB);
            tracker.track(rootC);
            rootA.setStatus("A-1");
            rootB.setStatus("B-1");
            rootC.setStatus("C-1");

            final ChangeSet forA = tracker.calculateChangesFor(rootA);
            final ChangeSet global = tracker.calculateChanges();

            assertThat(forA.changes()).hasSize(1);
            assertThat(forA.changes().get(0).target()).isSameAs(rootA);
            final Map<Object, ObjectChange> globalByTarget = indexByTarget(global);
            assertThat(forA.changes().get(0).changeTree())
                    .isEqualTo(globalByTarget.get(rootA).changeTree());
        }

        @Test
        @DisplayName("多根混合变更：计算某根只返回该根的 ObjectChange，其他根不参与")
        void mixedChanges_forOneRoot_shouldOnlyContainItsOwnChange() {
            final ChangeTracker tracker = new ChangeTracker(capability());
            final Order rootA = new Order(1L, "ORD-A");
            final Order rootB = new Order(2L, "ORD-B");
            tracker.track(rootA);
            tracker.track(rootB);
            rootA.setStatus("A-1");   // 仅 A 修改

            final ChangeSet forA = tracker.calculateChangesFor(rootA);
            final ChangeSet forB = tracker.calculateChangesFor(rootB);

            assertThat(forA.changes()).hasSize(1);
            assertThat(forA.changes().get(0).target()).isSameAs(rootA);
            assertThat(forB.changes()).isEmpty();   // B 无变更 → 空
        }
    }

    // ==================== Critical Path ====================

    @Nested
    @DisplayName("边界与参数（Critical Path）")
    class BoundariesAndParameters {

        @Test
        @DisplayName("从未追踪的实体 → 空 ChangeSet，不抛异常")
        void neverTracked_shouldReturnEmpty() {
            final ChangeTracker tracker = new ChangeTracker(capability());
            final Order order = new Order(1L, "ORD-001");

            final ChangeSet changeSet = tracker.calculateChangesFor(order);

            assertThat(changeSet.changes()).isEmpty();
        }

        @Test
        @DisplayName("stopTracking 后的实体 → 空 ChangeSet")
        void stopped_shouldReturnEmpty() {
            final ChangeTracker tracker = new ChangeTracker(capability());
            final Order order = new Order(1L, "ORD-001");
            tracker.track(order);
            tracker.stopTracking(order);
            order.setStatus("CONFIRMED");

            final ChangeSet changeSet = tracker.calculateChangesFor(order);

            assertThat(changeSet.changes()).isEmpty();
        }

        @Test
        @DisplayName("传入 null 应抛出 NullPointerException")
        void nullEntity_shouldThrowNpe() {
            final ChangeTracker tracker = new ChangeTracker(capability());

            assertThatThrownBy(() -> tracker.calculateChangesFor(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("identity 键语义：equals 相等的不同实例互不影响")
        void equalsEqualEntities_shouldNotAffectEachOther() {
            final ChangeTracker tracker = new ChangeTracker(capability());
            final IdEntity first = new IdEntity("same-id", "PENDING");
            final IdEntity second = new IdEntity("same-id", "PENDING");
            tracker.track(first);
            tracker.track(second);
            first.setStatus("CHANGED-A");

            final ChangeSet forFirst = tracker.calculateChangesFor(first);
            final ChangeSet forSecond = tracker.calculateChangesFor(second);

            assertThat(forFirst.changes()).hasSize(1);
            assertThat(forFirst.changes().get(0).target()).isSameAs(first);
            assertThat(forSecond.changes()).isEmpty();
        }
    }

    // ==================== 等价性与幂等（Fail Path 边界） ====================

    @Nested
    @DisplayName("与全局计算等价性 + 幂等（契约边界）")
    class EquivalenceAndIdempotency {

        @Test
        @DisplayName("逐实体调用与全局 calculateChanges() 按 target 过滤结果一致（顺序无关）")
        void perEntityCalls_shouldMatchGlobalFilteredByTarget() {
            final ChangeTracker tracker = new ChangeTracker(capability());
            final Order rootA = new Order(1L, "ORD-A");
            final Order rootB = new Order(2L, "ORD-B");
            final Order rootC = new Order(3L, "ORD-C");
            tracker.track(rootA);
            tracker.track(rootB);
            tracker.track(rootC);
            rootA.setStatus("A-1");
            rootC.setStatus("C-1");   // B 不变，混合场景

            final Map<Object, ObjectChange> perEntity = new IdentityHashMap<>();
            perEntity.putAll(indexByTarget(tracker.calculateChangesFor(rootA)));
            perEntity.putAll(indexByTarget(tracker.calculateChangesFor(rootB)));
            perEntity.putAll(indexByTarget(tracker.calculateChangesFor(rootC)));

            final Map<Object, ObjectChange> global = indexByTarget(tracker.calculateChanges());

            assertThat(perEntity.keySet()).containsExactlyInAnyOrder(rootA, rootC);
            assertThat(perEntity.keySet()).isEqualTo(global.keySet());
            assertThat(perEntity.get(rootA).changeTree()).isEqualTo(global.get(rootA).changeTree());
            assertThat(perEntity.get(rootC).changeTree()).isEqualTo(global.get(rootC).changeTree());
        }

        @Test
        @DisplayName("幂等：重复调用返回相同结果；调用后全局 calculateChanges() 结果不变（不更新基线）")
        void repeatedCalls_shouldBeIdempotentAndNotAdvanceBaseline() {
            final ChangeTracker tracker = new ChangeTracker(capability());
            final Order order = new Order(1L, "ORD-001");
            tracker.track(order);
            order.setStatus("CONFIRMED");

            final ChangeSet first = tracker.calculateChangesFor(order);
            final ChangeSet second = tracker.calculateChangesFor(order);

            assertThat(second.changes()).hasSize(1);
            assertThat(second.changes().get(0).changeTree())
                    .isEqualTo(first.changes().get(0).changeTree());

            // 调用 calculateChangesFor 不推进基线：全局计算仍能检出同一变更
            final ChangeSet global = tracker.calculateChanges();
            assertThat(global.changes()).hasSize(1);
            assertThat(global.changes().get(0).target()).isSameAs(order);
        }
    }
}