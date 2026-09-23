package com.nona.changeTracking.bench;

import com.nona.changeTracking.bench.sample.SampleAddress;
import com.nona.changeTracking.bench.sample.SampleAddressLeaf;
import com.nona.changeTracking.bench.sample.SampleAddressLink;
import com.nona.changeTracking.bench.sample.SampleMutator;
import com.nona.changeTracking.bench.sample.SampleOrder;
import com.nona.changeTracking.bench.sample.SampleOrderSummary;
import com.nona.changeTracking.bench.sample.SampleShape;
import com.nona.changeTracking.domain.model.tracking.ChangeTracker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DimensionScanState}: the iteration fixture is built from the scanned shape
 * through the frozen sample family, the tracking baseline is registered, and every iteration
 * rebuilds the fixture so no iteration inherits the state of the previous one.
 */
@DisplayName("DimensionScanState 维度扫描装配单元测试")
class DimensionScanStateUnitTest {

    @Test
    @DisplayName("默认形状的迭代装配应产出宽样本、集合 100 项、地址链深度 2，并装配追踪器")
    void setUpIteration_withDefaultShape_shouldBuildSampleOfTheScannedShape() {
        final ProbeScan scan = new ProbeScan(SampleShape.defaults());

        scan.setUpIteration();

        final SampleOrder sample = (SampleOrder) scan.sample();
        assertThat(sample.items()).hasSize(SampleShape.DEFAULT_COLLECTION_SIZE);
        assertThat(addressDepth(sample.address())).isEqualTo(SampleShape.DEFAULT_NESTING_DEPTH);
        assertThat(scan.shape()).isEqualTo(SampleShape.defaults());
        assertThat(scan.tracker()).isNotNull();
    }

    @Test
    @DisplayName("窄字段数形状应产出窄样本，下界（深度 1、集合 0）应产出叶子链与空集合")
    void setUpIteration_withNarrowLowerBoundShape_shouldFollowTheSampleFamily() {
        final ProbeScan scan = new ProbeScan(
                SampleShape.of(SampleShape.SUPPORTED_FIELD_COUNT_LOW, 1, 0));

        scan.setUpIteration();

        final SampleOrderSummary sample = (SampleOrderSummary) scan.sample();
        assertThat(sample.items()).isEmpty();
        assertThat(addressDepth(sample.address())).isEqualTo(1);
    }

    @Test
    @DisplayName("上界形状（深度 5、集合 1000）应产出对应规模的样本")
    void setUpIteration_withUpperBoundShape_shouldBuildDocumentedUpperLoad() {
        final ProbeScan scan = new ProbeScan(
                SampleShape.of(SampleShape.SUPPORTED_FIELD_COUNT_HIGH, 5, 1_000));

        scan.setUpIteration();

        final SampleOrder sample = (SampleOrder) scan.sample();
        assertThat(sample.items()).hasSize(1_000);
        assertThat(addressDepth(sample.address())).isEqualTo(5);
    }

    @Test
    @DisplayName("迭代装配应登记基线：装配后的样本修改可被 calculateChangesFor 观测")
    void setUpIteration_shouldRegisterTheTrackingBaseline() {
        final ProbeScan scan = new ProbeScan(SampleShape.defaults());
        scan.setUpIteration();

        SampleMutator.changeField(scan.sample(), "status");

        assertThat(scan.tracker().calculateChangesFor(scan.sample()).getLeafChanges()).isNotEmpty();
    }

    @Test
    @DisplayName("同一扫描状态的两次迭代装配应各自重建样本与追踪器")
    void setUpIteration_calledTwice_shouldRebuildSampleAndTracker() {
        final ProbeScan scan = new ProbeScan(SampleShape.defaults());
        scan.setUpIteration();
        final Object firstSample = scan.sample();
        final ChangeTracker firstTracker = scan.tracker();

        scan.setUpIteration();

        assertThat(scan.sample()).isNotSameAs(firstSample);
        assertThat(scan.tracker()).isNotSameAs(firstTracker);
    }

    /**
     * Returns the depth of the address chain, counting every element including the terminal leaf.
     *
     * @param address the chain head
     * @return the number of chain elements
     */
    private static int addressDepth(final SampleAddress address) {
        int depth = 0;
        SampleAddress current = address;
        while (current instanceof SampleAddressLink link) {
            depth++;
            current = link.next();
        }
        if (current instanceof SampleAddressLeaf) {
            depth++;
        }
        return depth;
    }

    /**
     * Scan state used by these tests: it carries the shape directly instead of deriving it from a
     * scanned dimension level.
     */
    private static final class ProbeScan extends DimensionScanState {

        private final SampleShape shape;

        /**
         * Creates a scan state over the given shape.
         *
         * @param shape the shape the fixture is built from
         */
        ProbeScan(final SampleShape shape) {
            this.shape = shape;
        }

        /**
         * Returns the shape handed to the constructor.
         *
         * @return the shape the fixture is built from
         */
        @Override
        public SampleShape shape() {
            return shape;
        }
    }
}
