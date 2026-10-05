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

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Sparse change path benchmark of the comparison strategy.
 * <p>
 * <b>Scan axes.</b> The path cost of the comparison pays off most in the sparse change load, so this
 * class scans the change level with everything else frozen: one scan state measures the default
 * shape (zero change versus a single scalar field change) and one measures the frozen deep
 * chain (zero change versus a single deepest leaf change, the path length load). Both levels are
 * applied through the frozen mutation entry point of the sample family, so no benchmark builds a
 * sample or mutates a field of its own.
 * <p>
 * <b>Precondition and measured operation.</b> The scenario is a tracked sample carrying a known
 * change against its baseline; the iteration fixture is the shared one of {@link DimensionScanState}
 * plus the scanned change level applied right after the assembly. {@code calculateChanges} is a side
 * effect free idempotent view, so no per invocation reset is declared and the measured call still runs
 * the whole comparison.
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
public class SparseChangePathBenchmark {

    /** Logger of the iteration assembly; the assembly runs outside the measured region. */
    private static final Logger log = LoggerFactory.getLogger(SparseChangePathBenchmark.class);

    /**
     * Shared scan state of the sparse change path: it applies the change level of the scanned
     * dimension to the sample of the iteration fixture assembled by the shared base class.
     * <p>
     * The sample keeps its changed state for the whole iteration, because the measured comparison has
     * no side effect on it; a scan state of this path therefore declares no per invocation reset.
     */
    public abstract static class SparseScanState extends DimensionScanState {

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
            applySparseChange(sample());
            log.info("Assembling the sparse change iteration fixture of {}", getClass().getSimpleName());
        }

        /**
         * Applies the change level of the scanned dimension to the sample of the current iteration.
         *
         * @param sample the sample assembled by {@link DimensionScanState#setUpIteration()}
         */
        public abstract void applySparseChange(Object sample);
    }

    /**
     * Sparse change level scan over the default shape: the number of changed scalar fields, every
     * other dimension at its {@link SampleShape} default.
     */
    public static class DefaultSparseChangeScan extends SparseScanState {

        /** No change level: the sample stays identical to its tracking baseline. */
        public static final int NO_CHANGE_LEVEL = 0;

        /** Single field level: exactly one scalar field of the sample changes. */
        public static final int SINGLE_FIELD_LEVEL = 1;

        /** Scalar field changed by the single field level. */
        private static final String SINGLE_FIELD_NAME = "status";

        /** Scanned number of changed scalar fields. */
        @Param({"0", "1"})
        public int sparseChangeLevel;

        /**
         * Returns the default shape: the scan measures the change itself, not a load size, so every
         * shape dimension stays at its default.
         *
         * @return the default shape of the wide sample
         */
        @Override
        public SampleShape shape() {
            return SampleShape.defaults();
        }

        /**
         * Applies the scanned sparse change level to the sample: nothing at the no change level, one
         * scalar field at the single field level, delegated to
         * {@link com.nona.changeTracking.bench.sample.SampleMutator}.
         *
         * @param sample the sample assembled by {@link DimensionScanState#setUpIteration()}
         */
        @Override
        public void applySparseChange(final Object sample) {
            switch (this.sparseChangeLevel) {
                case NO_CHANGE_LEVEL -> {
                    // No change level: the sample keeps the tracked baseline.
                }
                case SINGLE_FIELD_LEVEL -> SampleMutator.changeField(sample, SINGLE_FIELD_NAME);
                default -> throw new IllegalArgumentException(
                        "Unsupported sparseChangeLevel: " + this.sparseChangeLevel);
            }
        }
    }

    /**
     * Sparse change level scan over the frozen deep chain: zero change versus a single deepest leaf
     * change, the path length load shared with the view projection path.
     */
    public static class DeepChainChangeScan extends SparseScanState {

        /** No change level: the sample stays identical to its tracking baseline. */
        public static final int NO_CHANGE_LEVEL = 0;

        /** Deepest leaf level: exactly the deepest leaf of the nested address chain changes. */
        public static final int DEEPEST_LEAF_LEVEL = 1;

        /** Scanned number of changed deep leaves. */
        @Param({"0", "1"})
        public int deepChainChangeLevel;

        /**
         * Returns the frozen deep chain shape: the same chain the view projection path measures.
         *
         * @return the frozen deep chain shape
         */
        @Override
        public SampleShape shape() {
            return SampleShape.deepChain();
        }

        /**
         * Applies the scanned deep chain change level to the sample: nothing at the no change level,
         * the deepest leaf at the deepest leaf level, delegated to
         * {@link com.nona.changeTracking.bench.sample.SampleMutator}.
         *
         * @param sample the sample assembled by {@link DimensionScanState#setUpIteration()}
         */
        @Override
        public void applySparseChange(final Object sample) {
            switch (this.deepChainChangeLevel) {
                case NO_CHANGE_LEVEL -> {
                    // No change level: the sample keeps the tracked baseline.
                }
                case DEEPEST_LEAF_LEVEL -> SampleMutator.changeDeepestLeafField(sample);
                default -> throw new IllegalArgumentException(
                        "Unsupported deepChainChangeLevel: " + this.deepChainChangeLevel);
            }
        }
    }

    /**
     * Measures the change detection of the sample for one default shape sparse change level.
     *
     * @param state     scan state holding the fixture and its change level
     * @param blackhole consumer of the computed change set
     */
    @Benchmark
    public void calculateChangesBySparseChangeLevel(final DefaultSparseChangeScan state, final Blackhole blackhole) {
        Objects.requireNonNull(state, "state");
        blackhole.consume(state.tracker().calculateChanges());
    }

    /**
     * Measures the change detection of the sample for one deep chain change level.
     *
     * @param state     scan state holding the frozen deep chain and its change level
     * @param blackhole consumer of the computed change set
     */
    @Benchmark
    public void calculateChangesByDeepChainChangeLevel(final DeepChainChangeScan state, final Blackhole blackhole) {
        Objects.requireNonNull(state, "state");
        blackhole.consume(state.tracker().calculateChanges());
    }
}
