package com.nona.changeTracking.bench;

import com.nona.changeTracking.bench.sample.SampleLineItem;
import com.nona.changeTracking.bench.sample.SampleMutator;
import com.nona.changeTracking.bench.sample.SampleOrder;
import com.nona.changeTracking.bench.sample.SampleOrderSummary;
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
 * Unit tests for the scan states of {@link EndToEndBenchmark}: every scan varies exactly one
 * dimension level while the other dimensions stay at the {@link SampleShape} default, levels outside
 * the frozen scan set are rejected by the sample shape validation, and the per-invocation
 * precondition reset clears the tracking state and restores the item collection to the iteration
 * initial state.
 * <p>
 * The reset tests encode the two preconditions the measured call consumes: the sample must be
 * untracked, because {@code ChangeTracker.track} returns early for an already tracked entity, and the
 * collection must hold the iteration initial items again, because the measured call appends one item
 * and the collection would otherwise grow from round to round and distort the collection size
 * dimension. The reset has to reach both states without rebuilding the iteration fixture, otherwise
 * the rebuilding cost would be accounted to the measured operation by {@code -prof gc}.
 * <p>
 * The rounds of the last test replay the path declared by the benchmark class Javadoc -
 * {@code track}, one in place append and {@code calculateChanges} - through the public library and
 * sample family api, independently of the benchmark method and the JMH harness.
 */
@DisplayName("EndToEndBenchmark 扫描状态单元测试")
class EndToEndScanStateUnitTest {

    /** Number of path rounds replayed to show that every round starts from the same state. */
    private static final int ROUNDS = 3;

    /** Collection size level used by the reset tests, the smallest frozen level. */
    private static final int SMALL_COLLECTION_SIZE = 10;

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
    @DisplayName("越界档位（字段数 4/6、深度 0、集合规模 -1）应由样本形状校验拒绝")
    void shape_withOutOfRangeLevel_shouldBeRejectedByTheSampleShapeValidation() {
        final EndToEndBenchmark.FieldCountScan fieldCountScan = new EndToEndBenchmark.FieldCountScan();
        fieldCountScan.fieldCount = SampleShape.SUPPORTED_FIELD_COUNT_LOW - 1;
        assertThatThrownBy(fieldCountScan::shape)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fieldCount");
        fieldCountScan.fieldCount = SampleShape.SUPPORTED_FIELD_COUNT_HIGH + 1;
        assertThatThrownBy(fieldCountScan::shape)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fieldCount");

        final EndToEndBenchmark.NestingDepthScan depthScan = new EndToEndBenchmark.NestingDepthScan();
        depthScan.nestingDepth = 1 - 1;
        assertThatThrownBy(depthScan::shape)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nestingDepth");

        final EndToEndBenchmark.CollectionSizeScan sizeScan = new EndToEndBenchmark.CollectionSizeScan();
        sizeScan.collectionSize = -1;
        assertThatThrownBy(sizeScan::shape)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("collectionSize");
    }

    @Test
    @DisplayName("复位应清空追踪态：样本不再参与变更计算")
    void resetPrecondition_shouldReturnTheSampleToTheUntrackedState() {
        final EndToEndBenchmark.FieldCountScan scan = fieldCountScan(SampleShape.SUPPORTED_FIELD_COUNT_HIGH);
        scan.setUpIteration();
        SampleMutator.addItem(scan.sample());
        assertThat(scan.tracker().calculateChangesFor(scan.sample()).getLeafChanges())
                .as("modification of the round")
                .hasSize(1);

        scan.resetPrecondition();

        assertThat(scan.tracker().captureBaseline().entities())
                .as("tracked entities after the reset")
                .doesNotContainKey(scan.sample());
        assertThat(scan.tracker().calculateChangesFor(scan.sample()).isEmpty())
                .as("changes of an untracked sample")
                .isTrue();
    }

    @Test
    @DisplayName("复位应把追加过的集合还原到迭代初始的大小与顺序")
    void resetPrecondition_shouldRestoreTheItemCollectionToTheIterationInitialState() {
        final EndToEndBenchmark.CollectionSizeScan scan = collectionSizeScan(SMALL_COLLECTION_SIZE);
        scan.setUpIteration();
        final List<Long> initialItemIds = itemIdsOf(scan.sample());
        SampleMutator.addItem(scan.sample());
        assertThat(itemIdsOf(scan.sample())).as("collection after the append").hasSize(SMALL_COLLECTION_SIZE + 1);

        scan.resetPrecondition();

        assertThat(itemIdsOf(scan.sample()))
                .as("item order after the reset")
                .containsExactlyElementsOf(initialItemIds);
    }

    @Test
    @DisplayName("样本未被修改时的复位应保持集合内容不变，并清除追踪态")
    void resetPrecondition_withoutAPrecedingModification_shouldKeepTheIterationState() {
        final EndToEndBenchmark.CollectionSizeScan scan = collectionSizeScan(SMALL_COLLECTION_SIZE);
        scan.setUpIteration();
        final List<Long> initialItemIds = itemIdsOf(scan.sample());

        scan.resetPrecondition();

        assertThat(itemIdsOf(scan.sample()))
                .as("item order of an unmodified sample after the reset")
                .containsExactlyElementsOf(initialItemIds);
        assertThat(scan.tracker().captureBaseline().entities())
                .as("tracked entities after the reset")
                .doesNotContainKey(scan.sample());
    }

    @Test
    @DisplayName("重复复位应幂等：样本与追踪器状态不再变化")
    void resetPrecondition_calledTwice_shouldBeIdempotent() {
        final EndToEndBenchmark.FieldCountScan scan = fieldCountScan(SampleShape.SUPPORTED_FIELD_COUNT_LOW);
        scan.setUpIteration();
        SampleMutator.addItem(scan.sample());

        scan.resetPrecondition();
        final List<Long> afterFirstReset = itemIdsOf(scan.sample());
        scan.resetPrecondition();

        assertThat(itemIdsOf(scan.sample()))
                .as("item order after the second reset")
                .containsExactlyElementsOf(afterFirstReset);
        assertThat(scan.tracker().captureBaseline().entities())
                .as("tracked entities after the second reset")
                .doesNotContainKey(scan.sample());
    }

    @Test
    @DisplayName("复位不应重建迭代装配：样本、追踪器与集合实例保持同一")
    void resetPrecondition_shouldKeepTheIterationFixtureInPlace() {
        final EndToEndBenchmark.FieldCountScan scan = fieldCountScan(SampleShape.SUPPORTED_FIELD_COUNT_HIGH);
        scan.setUpIteration();
        final Object sample = scan.sample();
        final ChangeTracker tracker = scan.tracker();
        final List<SampleLineItem> items = itemsOf(sample);
        final List<SampleLineItem> initialItems = List.copyOf(items);

        SampleMutator.addItem(sample);
        scan.resetPrecondition();

        assertThat(scan.sample()).as("sample of the iteration").isSameAs(sample);
        assertThat(scan.tracker()).as("tracker of the iteration").isSameAs(tracker);
        assertThat(itemsOf(sample)).as("item collection of the iteration").isSameAs(items);
        assertThat(itemsOf(sample))
                .as("item instances surviving the reset")
                .containsExactlyElementsOf(initialItems);
    }

    @Test
    @DisplayName("多轮全链路应每轮从同一起点重建基线，产出同等规模的变更集")
    void repeatedRounds_shouldRebuildTheWholePathFromTheSameStartingPoint() {
        final EndToEndBenchmark.NestingDepthScan scan = new EndToEndBenchmark.NestingDepthScan();
        scan.nestingDepth = 1;
        scan.setUpIteration();
        final List<Long> initialItemIds = itemIdsOf(scan.sample());

        for (int round = 0; round < ROUNDS; round++) {
            scan.resetPrecondition();
            scan.tracker().track(scan.sample());
            SampleMutator.addItem(scan.sample());

            assertThat(scan.tracker().calculateChanges().getLeafChanges())
                    .as("changes of round %d", round)
                    .hasSize(1);

            scan.resetPrecondition();

            assertThat(itemIdsOf(scan.sample()))
                    .as("sample of round %d after its reset", round)
                    .containsExactlyElementsOf(initialItemIds);
        }
    }

    /**
     * Creates a field count scan on the given level.
     *
     * @param fieldCount the scanned field count level
     * @return the scan state carrying that level
     */
    private static EndToEndBenchmark.FieldCountScan fieldCountScan(final int fieldCount) {
        final EndToEndBenchmark.FieldCountScan scan = new EndToEndBenchmark.FieldCountScan();
        scan.fieldCount = fieldCount;
        return scan;
    }

    /**
     * Creates a collection size scan on the given level.
     *
     * @param collectionSize the scanned collection size level
     * @return the scan state carrying that level
     */
    private static EndToEndBenchmark.CollectionSizeScan collectionSizeScan(final int collectionSize) {
        final EndToEndBenchmark.CollectionSizeScan scan = new EndToEndBenchmark.CollectionSizeScan();
        scan.collectionSize = collectionSize;
        return scan;
    }

    /**
     * Returns the concrete scan state classes declared by the benchmark class, ordered by name.
     *
     * @return the three dimension scan states
     */
    private static List<Class<?>> scanStates() {
        final List<Class<?>> states = new ArrayList<>();
        for (final Class<?> candidate : EndToEndBenchmark.class.getDeclaredClasses()) {
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

    /**
     * Returns the item collection of a sample root.
     *
     * @param sample a sample root created by the frozen sample family
     * @return the item collection of the sample
     * @throws IllegalStateException if the sample type is not a sample root of this family
     */
    private static List<SampleLineItem> itemsOf(final Object sample) {
        if (sample instanceof SampleOrder order) {
            return order.items();
        }
        if (sample instanceof SampleOrderSummary summary) {
            return summary.items();
        }
        throw new IllegalStateException("Unsupported sample type: " + sample.getClass().getName());
    }

    /**
     * Returns the business identifiers of the item collection, in collection order.
     *
     * @param sample a sample root created by the frozen sample family
     * @return the item identifiers in collection order
     */
    private static List<Long> itemIdsOf(final Object sample) {
        return itemsOf(sample).stream().map(SampleLineItem::id).toList();
    }
}
