package com.nona.changeTracking.bench;

import com.nona.changeTracking.bench.sample.SampleMutator;
import com.nona.changeTracking.bench.sample.SampleOrder;
import com.nona.changeTracking.bench.sample.SampleShape;
import com.nona.changeTracking.domain.model.tracking.ChangeTracker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openjdk.jmh.annotations.Param;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the scan states of {@link SnapshotBuildBenchmark}: every scan varies exactly one
 * dimension level while the other dimensions stay at the {@link SampleShape} default, levels outside
 * the frozen scan set are rejected by the sample shape validation, and the per-invocation
 * precondition reset returns the sample to the untracked state so the measured call rebuilds the
 * whole snapshot instead of taking the idempotent early return.
 */
@DisplayName("SnapshotBuildBenchmark 扫描状态单元测试")
class SnapshotScanStateUnitTest {

    @Test
    @DisplayName("每个扫描维度只变化本维档位，其余维保持 SampleShape 默认值")
    void shape_shouldVaryOnlyTheScannedDimension() throws Exception {
        for (final Class<?> stateType : scanStates()) {
            final Object state = stateType.getDeclaredConstructor().newInstance();
            final Field level = onlyParamField(stateType);
            for (final int value : levelsOf(level)) {
                level.set(state, value);

                assertThat(((DimensionScanState) state).shape())
                        .as("%s level %d", stateType.getSimpleName(), value)
                        .isEqualTo(expectedShape(level.getName(), value));
            }
        }
    }

    @Test
    @DisplayName("扫描状态经迭代装配后应产出对应档位的样本")
    void setUpIteration_shouldBuildTheSampleOfTheScannedLevel() {
        final SnapshotBuildBenchmark.CollectionSizeScan scan = new SnapshotBuildBenchmark.CollectionSizeScan();
        scan.collectionSize = 10;

        scan.setUpIteration();

        assertThat(((SampleOrder) scan.sample()).items()).hasSize(10);
    }

    @Test
    @DisplayName("越界档位（字段数 4/6、深度 0、集合规模 -1）应由样本形状校验拒绝")
    void shape_withOutOfRangeLevel_shouldBeRejectedByTheSampleShapeValidation() {
        final SnapshotBuildBenchmark.FieldCountScan fieldCountScan = new SnapshotBuildBenchmark.FieldCountScan();
        fieldCountScan.fieldCount = 4;
        assertThatThrownBy(fieldCountScan::shape)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fieldCount");
        fieldCountScan.fieldCount = 6;
        assertThatThrownBy(fieldCountScan::shape)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fieldCount");

        final SnapshotBuildBenchmark.NestingDepthScan depthScan = new SnapshotBuildBenchmark.NestingDepthScan();
        depthScan.nestingDepth = 0;
        assertThatThrownBy(depthScan::shape)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nestingDepth");

        final SnapshotBuildBenchmark.CollectionSizeScan sizeScan = new SnapshotBuildBenchmark.CollectionSizeScan();
        sizeScan.collectionSize = -1;
        assertThatThrownBy(sizeScan::shape)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("collectionSize");
    }

    @Test
    @DisplayName("重置应使样本回到未追踪态，且不重建样本与追踪器")
    void resetPrecondition_shouldReturnSampleToUntrackedStateWithoutRebuildingTheFixture() {
        final SnapshotBuildBenchmark.FieldCountScan scan = scanWith(SampleShape.SUPPORTED_FIELD_COUNT_HIGH);
        scan.setUpIteration();
        final Object sample = scan.sample();
        final ChangeTracker tracker = scan.tracker();
        SampleMutator.changeField(sample, "status");
        assertThat(tracker.calculateChangesFor(sample).getLeafChanges()).isNotEmpty();

        scan.resetPrecondition();

        assertThat(tracker.calculateChangesFor(sample).getLeafChanges()).isEmpty();
        assertThat(scan.sample()).isSameAs(sample);
        assertThat(scan.tracker()).isSameAs(tracker);
    }

    @Test
    @DisplayName("重置后的 track 应按当前状态重建基线，而不是命中幂等早返回")
    void trackAfterReset_shouldRebuildTheBaselineFromTheCurrentState() {
        final SnapshotBuildBenchmark.FieldCountScan scan = scanWith(SampleShape.SUPPORTED_FIELD_COUNT_LOW);
        scan.setUpIteration();
        SampleMutator.changeField(scan.sample(), "status");

        scan.resetPrecondition();
        scan.tracker().track(scan.sample());

        assertThat(scan.tracker().calculateChangesFor(scan.sample()).getLeafChanges()).isEmpty();
    }

    @Test
    @DisplayName("连续多轮的 重置→track 每轮都应重建完整快照")
    void repeatedResetAndTrackRounds_shouldRebuildTheSnapshotEveryRound() {
        final SnapshotBuildBenchmark.NestingDepthScan scan = new SnapshotBuildBenchmark.NestingDepthScan();
        scan.nestingDepth = 1;
        scan.setUpIteration();

        for (int round = 0; round < 3; round++) {
            SampleMutator.changeField(scan.sample(), "status");
            assertThat(scan.tracker().calculateChangesFor(scan.sample()).getLeafChanges())
                    .as("change of round %d", round)
                    .isNotEmpty();

            scan.resetPrecondition();
            scan.tracker().track(scan.sample());

            assertThat(scan.tracker().calculateChangesFor(scan.sample()).getLeafChanges())
                    .as("baseline rebuilt in round %d", round)
                    .isEmpty();
        }
    }

    /**
     * Creates a field count scan on the given level.
     *
     * @param fieldCount the scanned field count level
     * @return the scan state carrying that level
     */
    private static SnapshotBuildBenchmark.FieldCountScan scanWith(final int fieldCount) {
        final SnapshotBuildBenchmark.FieldCountScan scan = new SnapshotBuildBenchmark.FieldCountScan();
        scan.fieldCount = fieldCount;
        return scan;
    }

    /**
     * Returns the concrete scan state classes declared by the benchmark class, ordered by name.
     *
     * @return the three dimension scan states
     */
    private static List<Class<?>> scanStates() {
        final List<Class<?>> states = new ArrayList<>();
        for (final Class<?> candidate : SnapshotBuildBenchmark.class.getDeclaredClasses()) {
            if (DimensionScanState.class.isAssignableFrom(candidate) && !Modifier.isAbstract(candidate.getModifiers())) {
                states.add(candidate);
            }
        }
        states.sort(Comparator.comparing(Class::getSimpleName));
        return states;
    }

    /**
     * Returns the single {@code @Param} field of a scan state.
     *
     * @param state the scan state class
     * @return the scanned dimension field
     */
    private static Field onlyParamField(final Class<?> state) {
        return Arrays.stream(state.getDeclaredFields())
                .filter(field -> field.isAnnotationPresent(Param.class))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No @Param field on " + state.getSimpleName()));
    }

    /**
     * Returns the scanned levels declared by a {@code @Param} field.
     *
     * @param field the scanned dimension field
     * @return the declared levels in declaration order
     */
    private static List<Integer> levelsOf(final Field field) {
        return Arrays.stream(field.getAnnotation(Param.class).value()).map(Integer::parseInt).toList();
    }

    /**
     * Returns the shape expected for one dimension level, with the remaining dimensions at their
     * default.
     *
     * @param dimension the scanned dimension field name
     * @param level     the scanned level
     * @return the expected shape
     */
    private static SampleShape expectedShape(final String dimension, final int level) {
        return switch (dimension) {
            case "fieldCount" -> SampleShape.of(level, SampleShape.DEFAULT_NESTING_DEPTH,
                    SampleShape.DEFAULT_COLLECTION_SIZE);
            case "nestingDepth" -> SampleShape.of(SampleShape.DEFAULT_FIELD_COUNT, level,
                    SampleShape.DEFAULT_COLLECTION_SIZE);
            case "collectionSize" -> SampleShape.of(SampleShape.DEFAULT_FIELD_COUNT,
                    SampleShape.DEFAULT_NESTING_DEPTH, level);
            default -> throw new IllegalArgumentException("Unknown dimension: " + dimension);
        };
    }
}