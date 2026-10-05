package com.nona.changeTracking.bench;

import com.nona.changeTracking.bench.sample.SampleMutator;
import com.nona.changeTracking.bench.sample.SampleShape;
import com.nona.changeTracking.domain.model.changeset.ChangeSet;
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

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Change view projection benchmark.
 * <p>
 * <b>Scan axis.</b> The change level is scanned with everything else frozen: no change against the
 * tracking baseline, and the single deepest leaf change of the frozen deep chain. Both levels are
 * applied through {@link SampleMutator}, the frozen mutation entry point of the sample family, so no
 * benchmark builds a sample or mutates a field of its own; the load is the frozen
 * {@link SampleShape#deepChain()} chain the path length carriers of the comparison and the view
 * projection path share.
 * <p>
 * <b>Carriers.</b> Four measured operations of the same load: the complete view only
 * ({@link #projectAllChangesByViewChangeLevel}), the leaf view only
 * ({@link #projectLeafChangesByViewChangeLevel}), the first acquisition followed by a repeated
 * acquisition of the complete view ({@link #projectAllChangesRepeatedlyByViewChangeLevel}), and the
 * whole chain of the existing capability — {@code calculateChanges()} plus both view entries —
 * ({@link #calculateChangesAndProjectByViewChangeLevel}). The three view only carriers take the
 * change set computed in the iteration assembly, so their measured region holds the projection
 * alone and the gain of one entry is not diluted by the comparison; the chain carrier keeps the
 * comparison in the measured region, so the end to end effect of the projection is measurable.
 * <p>
 * <b>Precondition and measured operation.</b> The iteration fixture of the shared
 * {@link DimensionScanState} assembly (sample, tracker of the existing default capability, baseline
 * registration) plus the scanned change level and the change set of that level, both taken right
 * after the assembly. {@code calculateChanges} and the two view entries are side effect free
 * idempotent views, so no per invocation reset is declared and the measured calls still rebuild the
 * requested view instead of returning a stored result.
 * <p>
 * <b>Before and after comparison.</b> The two runs this carrier feeds must carry the same strategy
 * versions and the same load, and differ in the projection implementation alone: the carrier
 * consumes the public view API only, so the same class file runs against the build before and after
 * the projection change, and the entry keys of the two runs pair by benchmark name and parameters.
 * The strategy version is never part of the entry key; it is recorded as run metadata by the
 * archive, as the module does for every carrier.
 * <p>
 * <b>Frozen parameterization pattern.</b> Class level annotations identical to the module smoke
 * benchmark, one scan state per {@code @Benchmark} method, each carrying exactly one {@code @Param}
 * dimension, samples created only through the frozen sample family, results consumed through the
 * blackhole.
 * <p>
 * <b>Logging.</b> Logging belongs to the iteration level assembly; the measured bodies carry no log
 * entry, because they run once per invocation.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class ViewProjectionBenchmark {

    /** Logger of the iteration assembly; the assembly runs outside the measured region. */
    private static final Logger log = LoggerFactory.getLogger(ViewProjectionBenchmark.class);

    /**
     * Shared scan state of the view projection path: it assembles the deep chain fixture of the
     * shared base class, applies the scanned change level to it and takes the change set of that
     * level, so every carrier starts from the same tracked, changed and already computed change set.
     * <p>
     * The sample keeps its changed state for the whole iteration, because neither the measured
     * comparison nor a view entry has a side effect on it; a scan state of this path therefore
     * declares no per invocation reset.
     */
    public abstract static class ViewScanState extends DimensionScanState {

        /** No change level: the sample stays identical to its tracking baseline. */
        public static final int NO_CHANGE_LEVEL = 0;

        /** Deepest leaf level: exactly the deepest leaf of the nested chain changes. */
        public static final int DEEPEST_LEAF_LEVEL = 1;

        /** The change set of the scanned level, computed by the iteration assembly. */
        private ChangeSet changeSet;

        /**
         * Assembles the iteration fixture of the shared base class, applies the change level of this
         * scan to the freshly assembled sample and takes the change set of the scanned level. JMH
         * invokes the collected set up methods of the declaring base class and of this override
         * separately, so one iteration assembles twice; every invocation is an assembly followed by
         * its level application and its change set, which leaves the precondition of the iteration
         * correct. This hook runs at iteration level, outside the measured region.
         */
        @Override
        @Setup(Level.Iteration)
        public void setUpIteration() {
            super.setUpIteration();
            applyViewChange(sample());
            this.changeSet = tracker().calculateChanges();
            log.info("Assembling the view projection iteration fixture of {}", getClass().getSimpleName());
        }

        /**
         * Returns the frozen deep chain shape: the load both the comparison path length carriers and
         * the view projection carriers of this work measure.
         *
         * @return the frozen deep chain shape
         */
        @Override
        public SampleShape shape() {
            return SampleShape.deepChain();
        }

        /**
         * Returns the scanned change level of this scan state, declared by the concrete state as its
         * single {@code @Param} dimension.
         *
         * @return the scanned change level
         */
        public abstract int viewChangeLevel();

        /**
         * Applies the change level of the scanned dimension to the sample of the current iteration:
         * nothing at the no change level, the deepest leaf at the deepest leaf level, delegated to
         * {@link SampleMutator#changeDeepestLeafField(Object)}.
         *
         * @param sample the sample assembled by {@link DimensionScanState#setUpIteration()}
         * @throws IllegalArgumentException if the scanned level is outside the frozen scan set
         */
        public void applyViewChange(final Object sample) {
            switch (viewChangeLevel()) {
                case NO_CHANGE_LEVEL -> {
                    // No change level: the sample keeps the tracked baseline.
                }
                case DEEPEST_LEAF_LEVEL -> SampleMutator.changeDeepestLeafField(sample);
                default -> throw new IllegalArgumentException(
                        "Unsupported viewChangeLevel: " + viewChangeLevel());
            }
        }

        /**
         * Returns the change set of the scanned level, computed by the iteration assembly.
         *
         * @return the change set the measured view entry projects
         */
        public ChangeSet changeSet() {
            return changeSet;
        }
    }

    /**
     * Scan state of the complete view carrier: the flat view holding every container and every leaf.
     */
    public static class CompleteViewScan extends ViewScanState {

        /** Scanned change level of the complete view carrier. */
        @Param({"0", "1"})
        public int viewChangeLevel;

        /**
         * {@inheritDoc}
         */
        @Override
        public int viewChangeLevel() {
            return viewChangeLevel;
        }
    }

    /**
     * Scan state of the leaf view carrier: the flat view holding the leaves only.
     */
    public static class LeafViewScan extends ViewScanState {

        /** Scanned change level of the leaf view carrier. */
        @Param({"0", "1"})
        public int viewChangeLevel;

        /**
         * {@inheritDoc}
         */
        @Override
        public int viewChangeLevel() {
            return viewChangeLevel;
        }
    }

    /**
     * Scan state of the repeated acquisition carrier: the same complete view acquired twice.
     */
    public static class RepeatedViewScan extends ViewScanState {

        /** Scanned change level of the repeated acquisition carrier. */
        @Param({"0", "1"})
        public int viewChangeLevel;

        /**
         * {@inheritDoc}
         */
        @Override
        public int viewChangeLevel() {
            return viewChangeLevel;
        }
    }

    /**
     * Scan state of the whole chain carrier: {@code calculateChanges()} plus both view entries of
     * the resulting change set.
     */
    public static class ViewChainScan extends ViewScanState {

        /** Scanned change level of the whole chain carrier. */
        @Param({"0", "1"})
        public int viewChangeLevel;

        /**
         * {@inheritDoc}
         */
        @Override
        public int viewChangeLevel() {
            return viewChangeLevel;
        }
    }

    /**
     * Measures the projection of the complete view of the change set of the scanned level; the
     * change set is taken by the iteration assembly, so the comparison stays outside the measured
     * region.
     *
     * @param state     scan state holding the fixture, its change level and the change set
     * @param blackhole consumer of the projected view
     */
    @Benchmark
    public void projectAllChangesByViewChangeLevel(final CompleteViewScan state, final Blackhole blackhole) {
        Objects.requireNonNull(state, "state");
        blackhole.consume(state.changeSet().getAllChanges());
    }

    /**
     * Measures the projection of the leaf view of the change set of the scanned level; the change set
     * is taken by the iteration assembly, so the comparison stays outside the measured region.
     *
     * @param state     scan state holding the fixture, its change level and the change set
     * @param blackhole consumer of the projected view
     */
    @Benchmark
    public void projectLeafChangesByViewChangeLevel(final LeafViewScan state, final Blackhole blackhole) {
        Objects.requireNonNull(state, "state");
        blackhole.consume(state.changeSet().getLeafChanges());
    }

    /**
     * Measures the first acquisition and the repeated acquisition of the complete view of the change
     * set of the scanned level. The projection rebuilds the view on every call instead of returning a
     * stored result, so the measured region holds both acquisitions and the repeat cost is visible.
     *
     * @param state     scan state holding the fixture, its change level and the change set
     * @param blackhole consumer of the projected views
     */
    @Benchmark
    public void projectAllChangesRepeatedlyByViewChangeLevel(final RepeatedViewScan state, final Blackhole blackhole) {
        Objects.requireNonNull(state, "state");
        blackhole.consume(state.changeSet().getAllChanges());
        blackhole.consume(state.changeSet().getAllChanges());
    }

    /**
     * Measures the whole chain of the existing default capability: the change detection of the
     * sample plus both view entries of its result. The comparison stays in the measured region, so
     * the end to end effect of the projection is visible next to the view only carriers.
     *
     * @param state     scan state holding the fixture, its change level and the change set
     * @param blackhole consumer of the projected views
     */
    @Benchmark
    public void calculateChangesAndProjectByViewChangeLevel(final ViewChainScan state, final Blackhole blackhole) {
        Objects.requireNonNull(state, "state");
        final ChangeSet recomputed = state.tracker().calculateChanges();
        blackhole.consume(recomputed.getAllChanges());
        blackhole.consume(recomputed.getLeafChanges());
    }
}
