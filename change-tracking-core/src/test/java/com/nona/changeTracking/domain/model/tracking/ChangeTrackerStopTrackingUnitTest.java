package com.nona.changeTracking.domain.model.tracking;

import com.nona.changeTracking.domain.capability.TrackingCapability;
import com.nona.changeTracking.domain.model.changeset.ChangeNode;
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
 * ChangeTracker 停止追踪（stopTracking）的场景测试。
 * <p>
 * 锁定 stopTracking 的完整契约：
 * <ul>
 *   <li>Happy：停止后不产变更（停止时点即生效、停止后修改无关）、多根场景一致性；</li>
 *   <li>Critical：幂等（重复停止、从未追踪对象停止）、null → NPE、可恢复性
 *       （停止后重新 track 恢复追踪；重新 track 以调用时刻状态为基线）、
 *       captureBaseline 视图一致性、identity 键语义；</li>
 *   <li>Fail path：与全局计算等价性——停止实体不产生任何 ObjectChange，
 *       等价于从未追踪。</li>
 * </ul>
 */
@DisplayName("ChangeTracker 停止追踪测试")
class ChangeTrackerStopTrackingUnitTest {

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

    /** equals 按 id 相等的实体：验证 stopTracking 的 identity 键语义（结合 IdentityHashMap）。 */
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

    /**
     * 创建用于测试的默认追踪能力实例。
     *
     * @return 支持 ValueNodeSnapshot 的默认追踪能力。
     */
    private static TrackingCapability<ValueNodeSnapshot> capability() {
        return new DefaultTrackingCapabilityProvider().create();
    }

    // ==================== Happy Path ====================

    @Nested
    @DisplayName("停止追踪后不产变更（Happy Path）")
    class StopTrackingEffect {

        @Test
        @DisplayName("track 后停止追踪：即使停止前已有修改，也不产生变更（停止时点即生效）")
        void stopTracking_afterMutation_shouldDropPendingChanges() {
            final ChangeTracker tracker = new ChangeTracker(capability());
            final Order order = new Order(1L, "ORD-001");
            tracker.track(order);
            order.setStatus("CONFIRMED");

            tracker.stopTracking(order);

            final ChangeSet changeSet = tracker.calculateChanges();
            assertThat(changeSet.isEmpty()).isTrue();
            assertThat(changeSet.getLeafChanges()).isEmpty();
        }

        @Test
        @DisplayName("停止后实体再被修改：不产生变更（等价于从未追踪）")
        void stopTracking_thenMutation_shouldNotProduceChanges() {
            final ChangeTracker tracker = new ChangeTracker(capability());
            final Order order = new Order(1L, "ORD-001");
            tracker.track(order);
            tracker.stopTracking(order);

            order.setStatus("CONFIRMED");

            final ChangeSet changeSet = tracker.calculateChanges();
            assertThat(changeSet.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("多根场景：停止一个根不影响其他根的变更产出与 target 归属")
        void stopTracking_multipleRoots_shouldExcludeOnlyStoppedRoot() {
            final ChangeTracker tracker = new ChangeTracker(capability());
            final Order rootA = new Order(1L, "ORD-A");
            final Order rootB = new Order(2L, "ORD-B");
            tracker.track(rootA);
            tracker.track(rootB);
            rootA.setStatus("CONFIRMED");
            rootB.setStatus("SHIPPED");

            tracker.stopTracking(rootB);

            final ChangeSet changeSet = tracker.calculateChanges();
            assertThat(changeSet.changes()).hasSize(1);
            assertThat(changeSet.changes().get(0).target()).isSameAs(rootA);
            assertThat(changeSet.changes().get(0).changeTree()).isNotNull();
        }
    }

    // ==================== Critical Path ====================

    @Nested
    @DisplayName("幂等与参数边界（Critical Path）")
    class IdempotencyAndParameters {

        @Test
        @DisplayName("对从未追踪的对象调用 stopTracking 应无副作用（不抛异常、状态不变）")
        void stopTracking_neverTracked_shouldBeNoOp() {
            final ChangeTracker tracker = new ChangeTracker(capability());
            final Order order = new Order(1L, "ORD-001");

            tracker.stopTracking(order);

            assertThat(tracker.calculateChanges().isEmpty()).isTrue();
        }

        @Test
        @DisplayName("重复调用 stopTracking 应幂等：第二次调用无异常且状态不变")
        void stopTracking_duplicate_shouldBeIdempotent() {
            final ChangeTracker tracker = new ChangeTracker(capability());
            final Order order = new Order(1L, "ORD-001");
            tracker.track(order);
            tracker.stopTracking(order);

            tracker.stopTracking(order); // 重复停止

            assertThat(tracker.calculateChanges().isEmpty()).isTrue();
        }

        @Test
        @DisplayName("stopTracking 传入 null 应抛出 NullPointerException")
        void stopTracking_null_shouldThrowNpe() {
            final ChangeTracker tracker = new ChangeTracker(capability());

            assertThatThrownBy(() -> tracker.stopTracking(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("可恢复性（Critical Path）")
    class Recoverability {

        @Test
        @DisplayName("停止后重新 track 应恢复追踪：后续修改重新产生变更（以新基线比较）")
        void stopTracking_thenTrack_shouldResumeTracking() {
            final ChangeTracker tracker = new ChangeTracker(capability());
            final Order order = new Order(1L, "ORD-001");
            tracker.track(order);
            tracker.stopTracking(order);

            tracker.track(order); // 恢复：重新建立基线
            order.setStatus("CONFIRMED");

            final ChangeSet changeSet = tracker.calculateChanges();
            assertThat(changeSet.isEmpty()).isFalse();
            assertThat(changeSet.changes()).hasSize(1);
            assertThat(changeSet.changes().get(0).target()).isSameAs(order);
        }

        @Test
        @DisplayName("停止到重新 track 之间发生的修改不产生变更（重新 track 以当前状态为基线）")
        void stopTracking_retrackAfterMutation_shouldUseCurrentStateAsBaseline() {
            final ChangeTracker tracker = new ChangeTracker(capability());
            final Order order = new Order(1L, "ORD-001");
            tracker.track(order);
            order.setStatus("CONFIRMED");
            tracker.stopTracking(order);

            tracker.track(order); // 重新登记：基线 = 当前（已修改）状态

            final ChangeSet changeSet = tracker.calculateChanges();
            assertThat(changeSet.isEmpty()).isTrue();
        }
    }

    @Nested
    @DisplayName("与基线/identity 语义的一致性（Critical Path）")
    class BaselineAndIdentityConsistency {

        @Test
        @DisplayName("停止追踪后 captureBaseline 不应再包含该实体（基线视图只反映当前追踪集合）")
        void stopTracking_shouldRemoveEntityFromExportedBaseline() {
            final ChangeTracker tracker = new ChangeTracker(capability());
            final Order order = new Order(1L, "ORD-001");
            final Order other = new Order(2L, "ORD-002");
            tracker.track(order);
            tracker.track(other);

            final BaselineSnapshot before = tracker.captureBaseline();
            assertThat(before.entities()).containsKeys(order, other);

            tracker.stopTracking(order);

            final BaselineSnapshot after = tracker.captureBaseline();
            assertThat(after.entities()).doesNotContainKey(order);
            assertThat(after.entities()).containsKey(other);
        }

        @Test
        @DisplayName("identity 键语义：equals 相等的不同实例，停止其中一个不影响另一个的追踪")
        void stopTracking_equalsEqualEntities_shouldStopOnlyTheExactInstance() {
            final ChangeTracker tracker = new ChangeTracker(capability());
            final IdEntity first = new IdEntity("same-id", "PENDING");
            final IdEntity second = new IdEntity("same-id", "PENDING");
            tracker.track(first);
            tracker.track(second);

            first.setStatus("CHANGED-A");
            second.setStatus("CHANGED-B");
            tracker.stopTracking(first);

            final ChangeSet changeSet = tracker.calculateChanges();
            assertThat(changeSet.changes()).hasSize(1);
            assertThat(changeSet.changes().get(0).target()).isSameAs(second);
        }
    }

    // ==================== Fail Path（契约边界） ====================

    @Nested
    @DisplayName("与全局计算等价性（Fail Path 边界）")
    class GlobalEquivalence {

        @Test
        @DisplayName("多根停止一者后：变更集与从未追踪该实体的等价 tracker 按 target 逐一致")
        void stopTracking_shouldMatchFreshTrackerWithoutStoppedRoot() {
            final ChangeTracker tracker = new ChangeTracker(capability());
            // fresh 与主 tracker 在 rootA/rootC 上保持同构追踪历史：同样在修改前 track
            // （track 以调用时刻状态为基线——若在修改后才 track，参照基线即修改后状态，等价性断言不成立）
            final ChangeTracker fresh = new ChangeTracker(capability());
            final Order rootA = new Order(1L, "ORD-A");
            final Order rootB = new Order(2L, "ORD-B");
            final Order rootC = new Order(3L, "ORD-C");
            tracker.track(rootA);
            tracker.track(rootB);
            tracker.track(rootC);
            fresh.track(rootA); // 从未追踪 rootB：仅登记 rootA/rootC，与主 tracker 同基线时点
            fresh.track(rootC);
            rootA.setStatus("A-1");
            rootB.setStatus("B-1");
            rootC.setStatus("C-1");

            tracker.stopTracking(rootB);
            final ChangeSet actual = tracker.calculateChanges();

            final ChangeSet expected = fresh.calculateChanges();

            assertThat(actual.changes()).hasSize(expected.changes().size());
            final Map<Object, ObjectChange> actualByTarget = indexByTarget(actual);
            final Map<Object, ObjectChange> expectedByTarget = indexByTarget(expected);
            assertThat(actualByTarget.keySet()).containsExactlyInAnyOrder(rootA, rootC);
            assertThat(actualByTarget.get(rootA)).isEqualTo(expectedByTarget.get(rootA));
            assertThat(actualByTarget.get(rootC)).isEqualTo(expectedByTarget.get(rootC));
        }
    }

    // ==================== 辅助 ====================

    /**
     * 按 target（identity 键语义）索引变更，消除 IdentityHashMap 迭代顺序的影响。
     *
     * @param changeSet 待索引的变更集。
     * @return target → ObjectChange 的 identity 键映射。
     */
    private static Map<Object, ObjectChange> indexByTarget(final ChangeSet changeSet) {
        final Map<Object, ObjectChange> byTarget = new IdentityHashMap<>();
        for (final ObjectChange objectChange : changeSet.changes()) {
            byTarget.put(objectChange.target(), objectChange);
        }
        return byTarget;
    }
}