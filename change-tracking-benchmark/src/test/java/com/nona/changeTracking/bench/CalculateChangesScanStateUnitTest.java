package com.nona.changeTracking.bench;

import com.nona.changeTracking.bench.sample.SampleLineItem;
import com.nona.changeTracking.bench.sample.SampleOrder;
import com.nona.changeTracking.bench.sample.SampleShape;
import com.nona.changeTracking.domain.model.changeset.Change;
import com.nona.changeTracking.domain.model.changeset.ChangeSet;
import com.nona.changeTracking.domain.model.changeset.ItemAddedChange;
import com.nona.changeTracking.domain.model.changeset.ItemRemovedChange;
import com.nona.changeTracking.domain.model.changeset.ValueChange;
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
 * Unit tests for the scan states of {@link CalculateChangesBenchmark}: every level of both scan axes
 * keeps every shape dimension at its {@link SampleShape} default, the assembled iteration fixture
 * carries exactly the change the scanned level promises, a repeated {@code calculateChanges} call
 * stays on the whole comparison path without consuming the precondition, levels outside the frozen
 * scan set are rejected, and a second iteration assembly rebuilds the fixture and applies its level
 * exactly once.
 * <p>
 * The expected change counts come from the frozen semantics of the sample family and the change
 * detection path: a changed scalar field produces one value change, the three payload fields of a
 * replaced item produce three value changes, an addition and a removal produce one item added change
 * plus one item removed change, and an in place reorder of the same item instances matches every
 * item by identity and therefore produces no change at all. Change paths are never asserted by their
 * identifier text: an unregistered business identifier falls back to {@code System.identityHashCode}
 * and is not stable across runs.
 */
@DisplayName("CalculateChangesBenchmark 扫描状态单元测试")
class CalculateChangesScanStateUnitTest {

    @Test
    @DisplayName("两条轴的每个档位都应保持全部形状维默认值")
    void shape_shouldKeepEveryShapeDimensionAtItsDefault() throws Exception {
        for (final Class<?> stateType : scanStates()) {
            final Object state = stateType.getDeclaredConstructor().newInstance();
            final Field level = onlyParamField(stateType);
            for (final int value : levelsOf(level)) {
                level.set(state, value);

                assertThat(((DimensionScanState) state).shape())
                        .as("%s level %d", stateType.getSimpleName(), value)
                        .isEqualTo(SampleShape.defaults());
            }
        }
    }

    @Test
    @DisplayName("无变更档位：装配后样本与基线一致，变更集为空")
    void setUpIteration_withNoChangeLevel_shouldProduceAnEmptyChangeSet() {
        final CalculateChangesBenchmark.ChangedFieldCountScan scan =
                changeRatioScan(CalculateChangesBenchmark.ChangedFieldCountScan.NO_CHANGE_LEVEL);

        scan.setUpIteration();

        final ChangeSet changes = scan.tracker().calculateChanges();
        assertThat(changes.getLeafChanges()).isEmpty();
        assertThat(changes.getAllChanges()).isEmpty();
        assertThat(changes.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("单字段档位：装配后恰好一个标量字段变更")
    void setUpIteration_withSingleFieldLevel_shouldChangeExactlyOneScalarField() {
        final CalculateChangesBenchmark.ChangedFieldCountScan scan =
                changeRatioScan(CalculateChangesBenchmark.ChangedFieldCountScan.SINGLE_FIELD_LEVEL);

        scan.setUpIteration();

        assertThat(scan.tracker().calculateChanges().getLeafChanges())
                .hasSize(CalculateChangesBenchmark.ChangedFieldCountScan.SINGLE_FIELD_LEVEL)
                .allSatisfy(change -> assertThat(change).isInstanceOf(ValueChange.class));
    }

    @Test
    @DisplayName("全字段档位：装配后宽样本的全部标量字段变更")
    void setUpIteration_withAllFieldsLevel_shouldChangeEveryScalarField() {
        final CalculateChangesBenchmark.ChangedFieldCountScan scan =
                changeRatioScan(CalculateChangesBenchmark.ChangedFieldCountScan.ALL_FIELDS_LEVEL);

        scan.setUpIteration();

        assertThat(scan.tracker().calculateChanges().getLeafChanges())
                .hasSize(SampleShape.DEFAULT_FIELD_COUNT)
                .allSatisfy(change -> assertThat(change).isInstanceOf(ValueChange.class));
    }

    @Test
    @DisplayName("值替换形态：装配后一个集合项的三个字段变更，集合规模不变")
    void setUpIteration_withValueReplacementShape_shouldChangeThePayloadOfOneItem() {
        final CalculateChangesBenchmark.CollectionShapeScan scan =
                collectionShapeScan(CalculateChangesBenchmark.CollectionShapeScan.VALUE_REPLACEMENT_LEVEL);

        scan.setUpIteration();

        final List<Change> changes = scan.tracker().calculateChanges().getLeafChanges();
        assertThat(changes).hasSize(3).allSatisfy(change -> assertThat(change).isInstanceOf(ValueChange.class));
        assertThat(itemsOf(scan)).hasSize(SampleShape.DEFAULT_COLLECTION_SIZE);
    }

    @Test
    @DisplayName("增删形态：装配后一个项新增、一个项移除，集合规模不变")
    void setUpIteration_withAdditionAndRemovalShape_shouldAddOneAndRemoveOneItem() {
        final CalculateChangesBenchmark.CollectionShapeScan scan =
                collectionShapeScan(CalculateChangesBenchmark.CollectionShapeScan.ADDITION_AND_REMOVAL_LEVEL);

        scan.setUpIteration();

        final List<Change> changes = scan.tracker().calculateChanges().getLeafChanges();
        assertThat(changes).hasSize(2);
        assertThat(changes.stream().filter(change -> change instanceof ItemAddedChange)).hasSize(1);
        assertThat(changes.stream().filter(change -> change instanceof ItemRemovedChange)).hasSize(1);
        assertThat(itemsOf(scan)).hasSize(SampleShape.DEFAULT_COLLECTION_SIZE);
    }

    @Test
    @DisplayName("重排形态：装配后集合就地重排且变更集为空")
    void setUpIteration_withReorderShape_shouldReorderInPlaceWithoutChanges() {
        final CalculateChangesBenchmark.CollectionShapeScan scan =
                collectionShapeScan(CalculateChangesBenchmark.CollectionShapeScan.REORDER_LEVEL);

        scan.setUpIteration();

        final List<SampleLineItem> items = itemsOf(scan);
        assertThat(scan.tracker().calculateChanges().getLeafChanges()).isEmpty();
        assertThat(items).hasSize(SampleShape.DEFAULT_COLLECTION_SIZE);
        assertThat(items.getLast().id()).as("the first item moved to the end of the collection").isZero();
    }

    @Test
    @DisplayName("重复调用语义：反复 calculateChanges 保持变更条数与前置状态不变")
    void repeatedCalculateChanges_shouldKeepTheChangeCountAndThePrecondition() {
        final CalculateChangesBenchmark.ChangedFieldCountScan scan =
                changeRatioScan(CalculateChangesBenchmark.ChangedFieldCountScan.SINGLE_FIELD_LEVEL);
        scan.setUpIteration();
        final Object sample = scan.sample();
        final ChangeTracker tracker = scan.tracker();

        for (int round = 0; round < 3; round++) {
            assertThat(tracker.calculateChanges().getLeafChanges())
                    .as("change count of round %d", round)
                    .hasSize(CalculateChangesBenchmark.ChangedFieldCountScan.SINGLE_FIELD_LEVEL);
        }

        assertThat(scan.sample()).isSameAs(sample);
        assertThat(scan.tracker()).isSameAs(tracker);
    }

    @Test
    @DisplayName("两次迭代装配应各自重建 fixture，且档位变更只应用一次")
    void setUpIteration_calledTwice_shouldRebuildTheFixtureAndApplyTheLevelOnce() {
        final CalculateChangesBenchmark.ChangedFieldCountScan scan =
                changeRatioScan(CalculateChangesBenchmark.ChangedFieldCountScan.SINGLE_FIELD_LEVEL);
        scan.setUpIteration();
        final Object firstSample = scan.sample();
        final ChangeTracker firstTracker = scan.tracker();
        assertThat(firstTracker.calculateChanges().getLeafChanges()).hasSize(1);

        scan.setUpIteration();

        assertThat(scan.sample()).isNotSameAs(firstSample);
        assertThat(scan.tracker()).isNotSameAs(firstTracker);
        assertThat(scan.tracker().calculateChanges().getLeafChanges())
                .as("change count after the second assembly")
                .hasSize(1);
    }

    @Test
    @DisplayName("越界档位（变更字段数 -1/2/19/21、集合形态 -1/0/4）应被拒绝")
    void setUpIteration_withOutOfRangeLevel_shouldBeRejected() {
        for (final int level : List.of(-1, 2, 19, 21)) {
            final CalculateChangesBenchmark.ChangedFieldCountScan scan = changeRatioScan(level);

            assertThatThrownBy(scan::setUpIteration)
                    .as("change ratio level %d", level)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("changedFieldCount");
        }
        for (final int level : List.of(-1, 0, 4)) {
            final CalculateChangesBenchmark.CollectionShapeScan scan = collectionShapeScan(level);

            assertThatThrownBy(scan::setUpIteration)
                    .as("collection shape level %d", level)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("collectionShape");
        }
    }

    /**
     * Creates a change ratio scan on the given level.
     *
     * @param level the scanned number of changed scalar fields
     * @return the scan state carrying that level
     */
    private static CalculateChangesBenchmark.ChangedFieldCountScan changeRatioScan(final int level) {
        final CalculateChangesBenchmark.ChangedFieldCountScan scan =
                new CalculateChangesBenchmark.ChangedFieldCountScan();
        scan.changedFieldCount = level;
        return scan;
    }

    /**
     * Creates a collection shape scan on the given level.
     *
     * @param level the scanned collection change shape
     * @return the scan state carrying that level
     */
    private static CalculateChangesBenchmark.CollectionShapeScan collectionShapeScan(final int level) {
        final CalculateChangesBenchmark.CollectionShapeScan scan =
                new CalculateChangesBenchmark.CollectionShapeScan();
        scan.collectionShape = level;
        return scan;
    }

    /**
     * Returns the line item collection of the sample of a scan state.
     *
     * @param scan the scan state holding the sample of the current iteration
     * @return the line item collection of the sample
     */
    private static List<SampleLineItem> itemsOf(final DimensionScanState scan) {
        return new ArrayList<>(((SampleOrder) scan.sample()).items());
    }

    /**
     * Returns the concrete scan state classes declared by the benchmark class, ordered by name.
     *
     * @return the two scan states of this path
     */
    private static List<Class<?>> scanStates() {
        final List<Class<?>> states = new ArrayList<>();
        for (final Class<?> candidate : CalculateChangesBenchmark.class.getDeclaredClasses()) {
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
}
