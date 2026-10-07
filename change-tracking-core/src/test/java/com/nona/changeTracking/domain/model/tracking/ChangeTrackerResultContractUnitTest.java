package com.nona.changeTracking.domain.model.tracking;

import com.nona.changeTracking.domain.model.changeset.ChangeLocation;
import com.nona.changeTracking.domain.model.changeset.ChangeSet;
import com.nona.changeTracking.domain.model.changeset.ContainerChange;
import com.nona.changeTracking.domain.model.changeset.ObjectChange;
import com.nona.changeTracking.domain.model.changeset.ValueChange;
import com.nona.changeTracking.domain.capability.TrackingConfiguration;
import com.nona.changeTracking.internal.capability.DefaultTrackingCapability;
import com.nona.changeTracking.internal.snapshot.ValueNodeSnapshotStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ChangeTracker} 结果组织与失败契约单元测试。
 * <p>
 * 覆盖 US01/US05 的框架侧：非空结果直接绑定目标并作为目标根下的结果列表（无人工根容器）、无变化不创建
 * {@link ObjectChange}、多目标分别绑定；以及五类失败条件中属于结果接收侧的三种——策略返回 null 被拒绝
 * （不能当作空列表）、策略抛异常时本次计算明确失败且不推进基线、快照类型不匹配沿用既有类型检查失败语义，
 * 另加「结果列表含 null 元素」的边界拒绝。具体拒绝异常类型在本次统一为
 * {@link IllegalStateException}（结果契约违反），不新增业务错误码体系。
 */
@DisplayName("ChangeTracker 结果组织与失败契约单元测试")
class ChangeTrackerResultContractUnitTest {

    @Nested
    @DisplayName("结果组织")
    class ResultOrganization {

        @Test
        @DisplayName("字段变化：结果绑定原追踪目标并直接持有根下结果，不插入人工根容器")
        void fieldChange_shouldBindTargetAndHoldRootLevelResults() {
            final ChangeTracker tracker = new ChangeTracker(new DefaultTrackingCapability(TrackingConfiguration.empty()));
            final SimpleEntity entity = new SimpleEntity();
            tracker.track(entity);
            entity.status = "PAID";

            final ChangeSet changeSet = tracker.calculateChanges();

            assertThat(changeSet.changes()).hasSize(1);
            final ObjectChange objectChange = changeSet.changes().get(0);
            assertThat(objectChange.target()).isSameAs(entity);
            assertThat(objectChange.changes()).hasSize(1);
            assertThat(objectChange.changes().get(0)).isInstanceOf(ValueChange.class);
            assertThat(objectChange.changes().get(0).fullPath()).isEqualTo("status");
            assertThat(objectChange.changes()).noneMatch(change -> change.fullPath().isEmpty());
        }

        @Test
        @DisplayName("无净变化：不创建 ObjectChange，变更集为空")
        void noNetChange_shouldProduceEmptyChangeSet() {
            final ChangeTracker tracker = new ChangeTracker(new DefaultTrackingCapability(TrackingConfiguration.empty()));
            final SimpleEntity entity = new SimpleEntity();
            tracker.track(entity);
            entity.status = "PAID";
            entity.status = "CREATED";

            final ChangeSet changeSet = tracker.calculateChanges();

            assertThat(changeSet.isEmpty()).isTrue();
            assertThat(changeSet.changes()).isEmpty();
        }

        @Test
        @DisplayName("多目标：每个 ObjectChange 绑定各自目标身份")
        void multipleTargets_shouldBindTheirOwnTargets() {
            final ChangeTracker tracker = new ChangeTracker(new DefaultTrackingCapability(TrackingConfiguration.empty()));
            final SimpleEntity first = new SimpleEntity();
            final SimpleEntity second = new SimpleEntity();
            tracker.track(first);
            tracker.track(second);
            first.status = "PAID";
            second.status = "CANCELLED";

            final ChangeSet changeSet = tracker.calculateChanges();

            assertThat(changeSet.changes()).hasSize(2);
            assertThat(changeSet.changes()).extracting(ObjectChange::target).containsExactlyInAnyOrder(first, second);
            assertStatusChangePayload(changeSet, first, "PAID");
            assertStatusChangePayload(changeSet, second, "CANCELLED");
        }

        @Test
        @DisplayName("未追踪实体按根取变更返回空集，不抛异常")
        void untrackedEntity_shouldReturnEmptyChangeSet() {
            final ChangeTracker tracker = new ChangeTracker(new DefaultTrackingCapability(TrackingConfiguration.empty()));

            final ChangeSet changeSet = tracker.calculateChangesFor(new SimpleEntity());

            assertThat(changeSet.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("视图访问不推进基线：重复计算仍报告同一批变化")
        void viewAccess_shouldNotAdvanceTheBaseline() {
            final ChangeTracker tracker = new ChangeTracker(new DefaultTrackingCapability(TrackingConfiguration.empty()));
            final SimpleEntity entity = new SimpleEntity();
            tracker.track(entity);
            entity.status = "PAID";

            final ChangeSet first = tracker.calculateChanges();
            first.getAllChanges();
            first.getLeafChanges();
            final ChangeSet second = tracker.calculateChanges();

            assertThat(second.getLeafChanges()).hasSize(1);
            assertThat(second.getLeafChanges().get(0).fullPath()).isEqualTo("status");
        }

        /**
         * 断言指定目标的结果只含一处 status 字段变化，且其新值为期望业务值（按目标身份取结果，与顺序无关）。
         *
         * @param changeSet        多目标变更集
         * @param target           目标对象
         * @param expectedNewValue 期望的新值
         */
        private static void assertStatusChangePayload(final ChangeSet changeSet, final Object target,
                                                      final Object expectedNewValue) {
            final ObjectChange objectChange = changeSet.changes().stream()
                    .filter(change -> change.target() == target)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no ObjectChange is bound to the expected target"));
            assertThat(objectChange.changes()).hasSize(1);
            assertThat(objectChange.changes().get(0)).isInstanceOfSatisfying(ValueChange.class,
                    value -> assertThat(value.newValue()).isEqualTo(expectedNewValue));
        }
    }

    @Nested
    @DisplayName("失败契约（结果接收侧）")
    class FailureContract {

        @Test
        @DisplayName("策略返回 null：本次计算明确失败，不当作无变化")
        void nullComparisonResult_shouldBeRejected() {
            final ChangeTracker tracker = trackerWith(ScriptedTrackingSupport.ScriptedComparisonStrategy.returningNull());
            final SimpleEntity entity = new SimpleEntity();
            tracker.track(entity);

            assertThatThrownBy(tracker::calculateChanges).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("策略抛异常：传播原异常实例，不返回部分成功结果")
        void failingStrategy_shouldPropagateTheOriginalException() {
            final IllegalStateException failure = new IllegalStateException("strategy failure");
            final ChangeTracker tracker = trackerWith(ScriptedTrackingSupport.ScriptedComparisonStrategy.failing(failure));
            tracker.track(new SimpleEntity());
            tracker.track(new SimpleEntity());

            assertThatThrownBy(tracker::calculateChanges).isSameAs(failure);
        }

        @Test
        @DisplayName("多目标中前序目标已成功：整次计算仍以原异常失败，不返回部分成功结果")
        void partialSuccess_shouldNotBeReturned() {
            final IllegalStateException failure = new IllegalStateException("second target failed");
            final ValueChange status = new ValueChange(ChangeLocation.field(ChangeLocation.root(), "status"),
                    "CREATED", "PAID");
            final ChangeTracker tracker = new ChangeTracker(new ScriptedTrackingSupport.ValueNodeSnapshotCapability(
                    new ValueNodeSnapshotStrategy(TrackingConfiguration.empty()),
                    new ScriptedTrackingSupport.FailingAfterSuccessValueNodeComparisonStrategy(List.of(status), failure)));
            tracker.track(new SimpleEntity());
            tracker.track(new SimpleEntity());

            assertThatThrownBy(tracker::calculateChanges).isSameAs(failure);
        }

        @Test
        @DisplayName("策略异常不推进追踪基线：重复调用仍以同一失败结束")
        void failingStrategy_shouldNotAdvanceTheBaseline() {
            final IllegalStateException failure = new IllegalStateException("strategy failure");
            final ChangeTracker tracker = new ChangeTracker(new ScriptedTrackingSupport.ValueNodeSnapshotCapability(
                    new ValueNodeSnapshotStrategy(TrackingConfiguration.empty()),
                    new ScriptedTrackingSupport.FailingValueNodeComparisonStrategy(failure)));
            tracker.track(new SimpleEntity());

            assertThatThrownBy(tracker::calculateChanges).isSameAs(failure);
            assertThatThrownBy(tracker::calculateChanges).isSameAs(failure);
            assertThat(tracker.captureBaseline().entities()).hasSize(1);
        }

        @Test
        @DisplayName("快照类型与策略声明不匹配：沿用既有类型检查失败语义（比较前拒绝）")
        void incompatibleSnapshotType_shouldBeRejectedBeforeComparison() {
            final ChangeTracker tracker = new ChangeTracker(new ScriptedTrackingSupport.ScriptedCapability(
                    new ScriptedTrackingSupport.PlainSnapshotStrategy(),
                    new ScriptedTrackingSupport.ForeignTypeComparisonStrategy()));
            tracker.track(new SimpleEntity());

            assertThatThrownBy(tracker::calculateChanges).isInstanceOf(ClassCastException.class);
        }

        @Test
        @DisplayName("结果列表含 null 元素：拒绝而非静默丢弃")
        void nullElementInResultList_shouldBeRejected() {
            final ValueChange status = new ValueChange(ChangeLocation.field(ChangeLocation.root(), "status"), "a", "b");
            final ChangeTracker tracker = trackerWith(ScriptedTrackingSupport.ScriptedComparisonStrategy.returning(
                    Arrays.asList(status, null)));
            tracker.track(new SimpleEntity());

            assertThatThrownBy(tracker::calculateChanges).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("结果中的分组不属于其包含位置：在结果构建入口被拒绝并向外传播")
        void invalidContainmentInResult_shouldBeRejected() {
            final ChangeTracker tracker = trackerWith(ScriptedTrackingSupport.ScriptedComparisonStrategy.supplying(() ->
                    List.of(new ContainerChange(ChangeLocation.field(ChangeLocation.root(), "address"),
                            List.of(new ValueChange(ChangeLocation.field(ChangeLocation.root(), "name"), "a", "b"))))));
            tracker.track(new SimpleEntity());

            assertThatThrownBy(tracker::calculateChanges).isInstanceOf(IllegalArgumentException.class);
        }

        /**
         * 用脚本化比较策略装配真实能力单元与追踪器。
         *
         * @param comparisonStrategy 脚本化比较策略
         * @return 变更检测器
         */
        private ChangeTracker trackerWith(final ScriptedTrackingSupport.ScriptedComparisonStrategy comparisonStrategy) {
            return new ChangeTracker(new ScriptedTrackingSupport.ScriptedCapability(
                    new ScriptedTrackingSupport.PlainSnapshotStrategy(), comparisonStrategy));
        }
    }

    /**
     * 被追踪的简单实体：一个可修改的字段。
     */
    static final class SimpleEntity {

        /**
         * 状态字段。
         */
        String status = "CREATED";
    }
}
