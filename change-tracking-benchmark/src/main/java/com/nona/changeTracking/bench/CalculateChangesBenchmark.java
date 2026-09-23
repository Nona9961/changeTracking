package com.nona.changeTracking.bench;

import com.nona.changeTracking.bench.sample.SampleMutator;
import com.nona.changeTracking.bench.sample.SampleShape;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * Benchmark of the change detection path, {@code ChangeTracker.calculateChanges}, scanned over the
 * change ratio and the collection change shape of the coverage matrix.
 * <p>
 * <b>Scan axes</b>: the coverage matrix crosses the change ratio with the collection change shape,
 * but the two are scanned as two independent single dimension axes instead of one cartesian product:
 * the cartesian product would only repeat the axis B entries once per axis A level without adding
 * any measured operation. Axis A varies
 * the number of changed scalar fields ({@code 0} no change, {@code 1} single field,
 * {@value SampleShape#DEFAULT_FIELD_COUNT} every scalar field of the wide sample); axis B varies the
 * collection change shape (value replacement, addition and removal, reorder). Every level is applied
 * in place through {@link SampleMutator}, the frozen mutation entry point of the sample family, so no
 * benchmark builds samples or mutates fields of its own.
 * <p>
 * <b>Frozen parameterization pattern</b>, reused from {@link SnapshotBuildBenchmark}, which defines
 * it for the module: class level annotations identical to the module smoke benchmark, one nested
 * {@code public static} scan state class per scanned dimension extending
 * {@link DimensionScanState} with exactly one {@code public int} {@code @Param} field, one scan state
 * per {@code @Benchmark} method named {@code <operation>By<dimension>} taking that state plus a
 * {@link Blackhole}, samples created only by the sample family through
 * {@link DimensionScanState#setUpIteration()}, and real consumption of the measured result.
 * <p>
 * <b>Precondition and measured operation</b>: the scenario of this path is a sample carrying a
 * known change against its tracking baseline, so the iteration fixture is the shared one (sample,
 * tracker and baseline registration) plus the scanned change level applied right after the assembly.
 * <b>No per invocation reset is declared</b>, and none is needed: {@code calculateChanges} is a
 * side effect free idempotent view which neither updates the baseline nor clears a change set, the
 * sample is not modified by the measured call, and a repeated call still runs the whole comparison
 * instead of an early return (measured: five consecutive calls keep both the per call nanos and the
 * change counts flat). Because the precondition survives the measured call, a per invocation reset
 * would only add assembly work to {@code gc.alloc.rate.norm}: rebuilding the sample per invocation
 * allocates the same order of magnitude as one measured comparison, so the allocation metric would
 * report the fixture assembly instead of the diff path.
 * <p>
 * <b>Iteration assembly</b>: {@link DiffScanState#setUpIteration()} overrides the shared hook and
 * applies the scanned level after the shared assembly. JMH collects the set up method of the
 * declaring base class and the overriding declaration separately and invokes both, so the assembly
 * runs twice per iteration; each invocation is an assembly immediately followed by the level
 * application, so the last one leaves the precondition correct. The duplicated assembly stays at
 * iteration level and is amortized over the invocations of the iteration.
 * <p>
 * <b>Logging</b>: the measured bodies of this class run once per invocation and carry no log entry,
 * and neither do the sample and tracker accessors they call. Logging is confined to the iteration
 * level assembly, that is to {@link DiffScanState#setUpIteration()} and the shared assembly it
 * delegates to.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class CalculateChangesBenchmark {

    /** Logger of the iteration assembly; the assembly runs outside the measured region. */
    private static final Logger log = LoggerFactory.getLogger(CalculateChangesBenchmark.class);

    /**
     * Scan state of the change detection path: it applies the change level of the scanned dimension
     * to the sample of the iteration fixture assembled by the shared base class.
     * <p>
     * The sample keeps its changed state for the whole iteration, because the measured comparison
     * has no side effect on it; a scan state of this path therefore declares no
     * {@code @Setup(Level.Invocation)} hook.
     */
    public abstract static class DiffScanState extends DimensionScanState {

        /**
         * Assembles the iteration fixture of the shared base class and applies the change level of
         * this scan to the freshly assembled sample. JMH invokes the collected set up methods of the
         * declaring base class and of this override separately, so one iteration assembles twice;
         * every invocation is an assembly followed by its level application, which leaves the
         * precondition of the iteration correct. This hook runs at iteration level, outside the
         * measured region.
         */
        @Override
        @Setup(Level.Iteration)
        public void setUpIteration() {
            super.setUpIteration();
            applyChange(sample());
            log.info("Change level of {} applied to the iteration sample", getClass().getSimpleName());
        }

        /**
         * Applies the change level of the scanned dimension to the sample of the current iteration.
         *
         * @param sample the sample assembled by {@link DimensionScanState#setUpIteration()}
         * @throws IllegalArgumentException if the scanned level is outside the frozen scan set
         */
        public abstract void applyChange(Object sample);
    }

    /**
     * Change ratio scan: the number of changed scalar fields, every other dimension of the matrix at
     * its {@link SampleShape} default.
     * <p>
     * The scanned level is the number of scalar fields the sample changes against its baseline, so
     * the level value and the expected number of leaf changes are the same number.
     */
    public static class ChangedFieldCountScan extends DiffScanState {

        /** No change level: the sample stays identical to its tracking baseline. */
        public static final int NO_CHANGE_LEVEL = 0;

        /** Single field level: exactly one scalar field of the sample changes. */
        public static final int SINGLE_FIELD_LEVEL = 1;

        /** Full change level: every scalar field of the wide sample changes. */
        public static final int ALL_FIELDS_LEVEL = SampleShape.DEFAULT_FIELD_COUNT;

        /** Scalar field changed by the single field level. */
        private static final String SINGLE_FIELD_NAME = "status";

        /** Scanned number of changed scalar fields. */
        @Param({"0", "1", "20"})
        public int changedFieldCount;

        /**
         * Returns the default shape: both scan axes of this path measure the change itself, not a
         * load size, so every shape dimension stays at its default.
         *
         * @return the default shape of the wide sample
         */
        @Override
        public SampleShape shape() {
            return SampleShape.defaults();
        }

        /**
         * Applies the scanned number of changed scalar fields to the sample: nothing at the no
         * change level, one scalar field at the single field level and every scalar field at the
         * full change level. The mutation is delegated to {@link SampleMutator}, so the sample stays
         * a sample of the family and the level is interpreted here instead of by the family.
         *
         * @param sample the sample assembled by {@link DimensionScanState#setUpIteration()}
         * @throws IllegalArgumentException if the scanned level is outside the frozen scan set
         */
        @Override
        public void applyChange(final Object sample) {
            if (changedFieldCount == NO_CHANGE_LEVEL) {
                return;
            }
            if (changedFieldCount == SINGLE_FIELD_LEVEL) {
                SampleMutator.changeField(sample, SINGLE_FIELD_NAME);
                return;
            }
            if (changedFieldCount == ALL_FIELDS_LEVEL) {
                SampleMutator.changeAllFields(sample);
                return;
            }
            throw new IllegalArgumentException(
                    "Unsupported changedFieldCount level: " + changedFieldCount);
        }
    }

    /**
     * Collection change shape scan: the way the line item collection changes against the baseline,
     * every other dimension of the matrix at its {@link SampleShape} default.
     * <p>
     * All three shapes are in place operations on the same collection instance, so the collection
     * keeps the default size and change detection matches the items by their identity; the identity
     * text of a change path is never asserted, because an unregistered business identifier falls back
     * to {@code System.identityHashCode} and is not stable across runs.
     */
    public static class CollectionShapeScan extends DiffScanState {

        /** Value replacement shape: the payload of one existing item changes. */
        public static final int VALUE_REPLACEMENT_LEVEL = 1;

        /** Addition and removal shape: one item is added and one item is removed. */
        public static final int ADDITION_AND_REMOVAL_LEVEL = 2;

        /** Reorder shape: the collection is reordered in place without changing its items. */
        public static final int REORDER_LEVEL = 3;

        /** Index of the item replaced and of the item removed by the collection change shapes. */
        private static final int FIRST_ITEM_INDEX = 0;

        /** Scanned collection change shape. */
        @Param({"1", "2", "3"})
        public int collectionShape;

        /**
         * Returns the default shape: the scanned dimension is the change shape of the default
         * collection, so every shape dimension stays at its default.
         *
         * @return the default shape, carrying the default collection size
         */
        @Override
        public SampleShape shape() {
            return SampleShape.defaults();
        }

        /**
         * Applies the scanned collection change shape to the sample: the payload of one existing
         * item is replaced, one item is added and one is removed, or the collection is reordered in
         * place. Every shape is delegated to an existing in place operation of
         * {@link SampleMutator}, so the collection instance and its size stay part of the fixture
         * and the level is interpreted here instead of by the sample family.
         *
         * @param sample the sample assembled by {@link DimensionScanState#setUpIteration()}
         * @throws IllegalArgumentException if the scanned level is outside the frozen scan set
         */
        @Override
        public void applyChange(final Object sample) {
            if (collectionShape == VALUE_REPLACEMENT_LEVEL) {
                SampleMutator.replaceItem(sample, FIRST_ITEM_INDEX);
                return;
            }
            if (collectionShape == ADDITION_AND_REMOVAL_LEVEL) {
                SampleMutator.addItem(sample);
                SampleMutator.removeItem(sample, FIRST_ITEM_INDEX);
                return;
            }
            if (collectionShape == REORDER_LEVEL) {
                SampleMutator.reorderItems(sample);
                return;
            }
            throw new IllegalArgumentException(
                    "Unsupported collectionShape level: " + collectionShape);
        }
    }

    /**
     * Computes the changes of the sample for one changed field count level; the precondition stays
     * with the scan state, because the computed view has no side effect on it.
     *
     * @param state     scan state holding the fixture and its change level
     * @param blackhole consumer of the computed change set
     */
    @Benchmark
    public void calculateChangesByChangedFieldCount(final ChangedFieldCountScan state, final Blackhole blackhole) {
        blackhole.consume(state.tracker().calculateChanges());
    }

    /**
     * Computes the changes of the sample for one collection change shape level; the precondition
     * stays with the scan state, because the computed view has no side effect on it.
     *
     * @param state     scan state holding the fixture and its collection change shape
     * @param blackhole consumer of the computed change set
     */
    @Benchmark
    public void calculateChangesByCollectionShape(final CollectionShapeScan state, final Blackhole blackhole) {
        blackhole.consume(state.tracker().calculateChanges());
    }
}
