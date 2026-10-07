package com.nona.changeTracking.domain.model.tracking;

import com.nona.changeTracking.domain.capability.TrackingCapability;
import com.nona.changeTracking.domain.model.changeset.ChangeLocation;
import com.nona.changeTracking.domain.model.changeset.ChangeSet;
import com.nona.changeTracking.domain.model.changeset.ContainerChange;
import com.nona.changeTracking.domain.model.changeset.ValueChange;
import com.nona.changeTracking.domain.model.snapshot.ValueNodeSnapshot;
import com.nona.changeTracking.internal.capability.DefaultTrackingCapability;
import com.nona.changeTracking.internal.capability.DefaultTrackingCapabilityProvider;
import com.nona.changeTracking.domain.capability.TrackingConfiguration;
import com.nona.changeTracking.spi.TrackingCapabilityProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 非默认比较策略经既有 capability 与 tracker 入口工作的单元测试（AC05-1、AC05-2 框架侧）。
 * <p>
 * 策略与快照策略都是真实的非默认实现（脚本只决定策略返回什么结果），经
 * {@link TrackingCapability} 与 {@link ChangeTracker} 的公开入口调用，覆盖空结果、普通字段变化、
 * 根值变化与非法结果拒绝；并核对默认装配（显式 provider 与 ServiceLoader 注册）在统一模型下仍可用。
 */
@DisplayName("非默认比较策略契约单元测试")
class NonDefaultStrategyContractUnitTest {

    @Nested
    @DisplayName("非默认策略经 capability 与 tracker 入口")
    class NonDefaultStrategyThroughTracker {

        @Test
        @DisplayName("空结果：不产生 ObjectChange，变更集为空")
        void emptyResult_shouldProduceEmptyChangeSet() {
            final ChangeTracker tracker = trackerWith(ScriptedTrackingSupport.ScriptedComparisonStrategy.returning(List.of()));
            tracker.track(new SimpleEntity());

            final ChangeSet changeSet = tracker.calculateChanges();

            assertThat(changeSet.isEmpty()).isTrue();
            assertThat(changeSet.changes()).isEmpty();
        }

        @Test
        @DisplayName("普通字段变化：非默认策略的字段值变化结果绑定目标并进入两个视图")
        void fieldChange_shouldReachBothViews() {
            final ValueChange status = new ValueChange(ChangeLocation.field(ChangeLocation.root(), "status"), "CREATED", "PAID");
            final ChangeTracker tracker = trackerWith(ScriptedTrackingSupport.ScriptedComparisonStrategy.returning(List.of(status)));
            final SimpleEntity entity = new SimpleEntity();
            tracker.track(entity);

            final ChangeSet changeSet = tracker.calculateChanges();

            assertThat(changeSet.changes()).hasSize(1);
            assertThat(changeSet.changes().get(0).target()).isSameAs(entity);
            assertThat(changeSet.changes().get(0).changes()).containsExactly(status);
            assertThat(changeSet.getAllChanges()).containsExactly(status);
            assertThat(changeSet.getLeafChanges()).containsExactly(status);
        }

        @Test
        @DisplayName("根值变化：非默认策略返回的空路径原子变化合法并出现在两个视图")
        void rootValueChange_shouldBeLegal() {
            final ValueChange rootChange = new ValueChange(ChangeLocation.root(), 1, 2);
            final ChangeTracker tracker = trackerWith(ScriptedTrackingSupport.ScriptedComparisonStrategy.returning(List.of(rootChange)));
            tracker.track(new SimpleEntity());

            final ChangeSet changeSet = tracker.calculateChanges();

            assertThat(changeSet.getAllChanges()).containsExactly(rootChange);
            assertThat(changeSet.getLeafChanges()).containsExactly(rootChange);
        }

        @Test
        @DisplayName("非法结果：不含包含位置的分组在结果构建入口被拒绝，本次计算不成功")
        void invalidResult_shouldBeRejected() {
            final ChangeTracker tracker = trackerWith(ScriptedTrackingSupport.ScriptedComparisonStrategy.supplying(() ->
                    List.of(new ContainerChange(ChangeLocation.field(ChangeLocation.root(), "address"),
                            List.of(new ValueChange(ChangeLocation.field(ChangeLocation.root(), "street"), "a", "b"))))));
            tracker.track(new SimpleEntity());

            assertThatThrownBy(tracker::calculateChanges).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("空分组结果：构造即被拒绝，不会成为合法结果")
        void emptyGroupResult_shouldBeRejected() {
            final ChangeTracker tracker = trackerWith(ScriptedTrackingSupport.ScriptedComparisonStrategy.supplying(() ->
                    List.of(new ContainerChange(ChangeLocation.field(ChangeLocation.root(), "address"), List.of()))));
            tracker.track(new SimpleEntity());

            assertThatThrownBy(tracker::calculateChanges).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("默认装配仍可用（框架侧）")
    class DefaultAssembly {

        @Test
        @DisplayName("显式默认 provider 装配的能力单元可检出字段变化")
        void explicitDefaultProvider_shouldStillWork() {
            final DefaultTrackingCapabilityProvider provider = new DefaultTrackingCapabilityProvider();
            final TrackingCapability<ValueNodeSnapshot> capability = provider.create();
            final ChangeTracker tracker = new ChangeTracker(capability);
            final SimpleEntity entity = new SimpleEntity();
            tracker.track(entity);
            entity.status = "PAID";

            assertThat(provider.getName()).isEqualTo("default-reflection");
            assertThat(tracker.calculateChanges().getLeafChanges()).hasSize(1);
        }

        @Test
        @DisplayName("ServiceLoader 仍能发现默认 provider 并装配出可用能力")
        void serviceLoaderDiscovery_shouldStillWork() {
            TrackingCapabilityProvider discovered = null;
            for (final TrackingCapabilityProvider candidate : ServiceLoader.load(TrackingCapabilityProvider.class)) {
                if (DefaultTrackingCapabilityProvider.class.isInstance(candidate)) {
                    discovered = candidate;
                    break;
                }
            }
            assertThat(discovered).isNotNull();

            final ChangeTracker tracker = new ChangeTracker(discovered.create());
            final SimpleEntity entity = new SimpleEntity();
            tracker.track(entity);
            entity.status = "PAID";

            assertThat(tracker.calculateChanges().getLeafChanges()).hasSize(1);
        }

        @Test
        @DisplayName("默认能力使用默认比较策略（ValueNodeComparisonStrategy 的真实实现）")
        void defaultCapability_shouldUseTheDefaultComparisonStrategy() {
            final TrackingCapability<ValueNodeSnapshot> capability = new DefaultTrackingCapability(TrackingConfiguration.empty());

            assertThat(capability.getComparisonStrategy()).isNotNull();
            assertThat(capability.getComparisonStrategy().getSupportedSnapshotType()).isEqualTo(ValueNodeSnapshot.class);
        }
    }

    /**
     * 用脚本化比较策略装配真实能力单元与追踪器。
     *
     * @param comparisonStrategy 非默认比较策略
     * @return 变更检测器
     */
    private static ChangeTracker trackerWith(final ScriptedTrackingSupport.ScriptedComparisonStrategy comparisonStrategy) {
        return new ChangeTracker(new ScriptedTrackingSupport.ScriptedCapability(
                new ScriptedTrackingSupport.PlainSnapshotStrategy(), comparisonStrategy));
    }

    /**
     * 默认装配链路使用的简单实体。
     */
    static final class SimpleEntity {

        /**
         * 状态字段。
         */
        String status = "CREATED";
    }
}
