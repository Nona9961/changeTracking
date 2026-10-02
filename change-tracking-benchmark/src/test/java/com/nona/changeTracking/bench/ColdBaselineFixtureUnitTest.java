package com.nona.changeTracking.bench;

import com.nona.changeTracking.bench.sample.SampleFamily;
import com.nona.changeTracking.bench.sample.SampleMutator;
import com.nona.changeTracking.bench.sample.SampleShape;
import com.nona.changeTracking.domain.capability.TrackingCapability;
import com.nona.changeTracking.domain.model.snapshot.Snapshot;
import com.nona.changeTracking.domain.model.snapshot.ValueNode;
import com.nona.changeTracking.domain.model.tracking.BaselineSnapshot;
import com.nona.changeTracking.spi.TrackingCapabilityProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 单元测试 for {@link ColdBaselineFixture}: the fixture mirrors the unconfigured default capability
 * for samples of the frozen sample family, so a fork of the cold first use protocol can be prepared
 * without calling the target snapshot strategy or either type processing cache.
 * <p>
 * The equivalence assertion compares the fixture's hand built node tree against a real snapshot of
 * the same sample, which is exactly the independent verification run the design requires; the value
 * comparison also covers samples whose fields were mutated before the fixture ran, so a stale read
 * cannot pass unnoticed.
 */
@DisplayName("ColdBaselineFixture 冷态基线 fixture 单元测试")
class ColdBaselineFixtureUnitTest {

    @Test
    @DisplayName("默认形状的基线节点树应与未配置默认能力的真实快照等价")
    void baselineOf_defaultShape_shouldMatchTheRealStrategySnapshot() {
        final Object sample = SampleFamily.create(SampleShape.defaults());

        final BaselineSnapshot baseline = ColdBaselineFixture.baselineOf(sample);

        assertThat(baseline.entities()).containsOnlyKeys(sample);
        assertThat(baseline.entities().get(sample)).isEqualTo(realSnapshotOf(sample));
    }

    @Test
    @DisplayName("下界形状（深度 1、集合 0）与冻结深链的基线应与真实快照等价")
    void baselineOf_lowerBoundShapeAndDeepChain_shouldMatchTheRealStrategySnapshot() {
        final Object lowerBound = SampleFamily.create(
                SampleShape.of(SampleShape.SUPPORTED_FIELD_COUNT_LOW, 1, 0));
        final Object deepChain = SampleFamily.create(SampleShape.deepChain());

        assertThat(ColdBaselineFixture.baselineOf(lowerBound).entities().get(lowerBound))
                .isEqualTo(realSnapshotOf(lowerBound));
        assertThat(ColdBaselineFixture.baselineOf(deepChain).entities().get(deepChain))
                .isEqualTo(realSnapshotOf(deepChain));
    }

    @Test
    @DisplayName("修改后的样本应读取当前字段值，不复用构造时刻的值")
    void baselineOf_mutatedSample_shouldReadTheCurrentFieldValues() {
        final Object sample = SampleFamily.create(SampleShape.of(SampleShape.SUPPORTED_FIELD_COUNT_HIGH, 2, 10));
        SampleMutator.changeField(sample, "status");
        SampleMutator.changeDeepestLeafField(sample);

        final BaselineSnapshot first = ColdBaselineFixture.baselineOf(sample);

        assertThat(first.entities().get(sample)).isEqualTo(realSnapshotOf(sample));

        SampleMutator.changeField(sample, "status");
        final BaselineSnapshot second = ColdBaselineFixture.baselineOf(sample);

        assertThat(second.entities().get(sample)).isEqualTo(realSnapshotOf(sample));
        assertThat(second.entities().get(sample)).isNotEqualTo(first.entities().get(sample));
    }

    @Test
    @DisplayName("null 样本与不支持的样本类型应被拒绝")
    void baselineOf_unsupportedInput_shouldBeRejected() {
        assertThatThrownBy(() -> ColdBaselineFixture.baselineOf(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ColdBaselineFixture.baselineOf(new Object()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ColdBaselineFixture.baselineOf(java.util.List.of("not-a-sample")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Returns the real snapshot tree of the given sample through the unconfigured default capability
     * discovered on the public extension point; the fixture must reproduce exactly this tree without
     * calling that strategy. The module reaches everything it consumes through the public face, so the
     * verification uses the SPI instead of the core internal implementation.
     *
     * @param sample the sample to snapshot
     * @return the real snapshot tree of the sample
     */
    private static ValueNode realSnapshotOf(final Object sample) {
        return snapshotTreeOf(defaultProvider().create(), sample);
    }

    /**
     * Takes the snapshot tree of one capability in a generics safe way: the capability carries its own
     * snapshot type, and the domain face represents that data as a {@link ValueNode} tree.
     *
     * @param capability the capability to snapshot with
     * @param sample     the sample to snapshot
     * @param <S>        snapshot type of the capability
     * @return the snapshot tree of the sample
     */
    private static <S extends Snapshot<?>> ValueNode snapshotTreeOf(final TrackingCapability<S> capability,
                                                                    final Object sample) {
        return (ValueNode) capability.getSnapshotStrategy().createSnapshot(sample).getSnapshotData();
    }

    /**
     * Discovers the default capability provider through {@code ServiceLoader}.
     *
     * @return the provider whose capability mirrors the fixture's correspondence
     */
    private static TrackingCapabilityProvider defaultProvider() {
        for (final TrackingCapabilityProvider provider : ServiceLoader.load(TrackingCapabilityProvider.class)) {
            if (CacheStateBenchmark.DEFAULT_PROVIDER_NAME.equals(provider.getName())) {
                return provider;
            }
        }
        throw new IllegalStateException("Default provider not discovered through ServiceLoader");
    }
}