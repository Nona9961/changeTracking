package com.nona.changeTracking.bench;

import com.nona.changeTracking.api.ChangeTrackerFactory;
import com.nona.changeTracking.bench.sample.SampleFamily;
import com.nona.changeTracking.bench.sample.SampleGraphNode;
import com.nona.changeTracking.bench.sample.SampleMutator;
import com.nona.changeTracking.domain.model.tracking.ChangeTracker;
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
 * Shared subgraph reuse benchmark of the comparison strategy.
 * <p>
 * <b>Scan axis.</b> The graph topology is the scanned load and everything else is frozen: the plain
 * tree (no sharing, no cycle — the regression shape), the shared subgraph (two references
 * to the same child per level — the measured sharing shape), the cyclic graph (termination
 * shape) and the mixed graph (sharing and a cycle in one object graph). The change level is scanned
 * per topology: zero change versus a single root layer value change, so the reuse path and the
 * necessary single output are measured separately from the zero change path.
 * <p>
 * <b>Shape accounting.</b> The four shapes are built by the frozen sample family, never by this
 * class. For the frozen {@link com.nona.changeTracking.bench.sample.SampleFamily#GRAPH_DEPTH} the shared graph holds 17 independent
 * objects per side, 32 reference edges and 2^16 reachable leaf paths, while the plain tree holds 33
 * independent objects and 32 reference edges. Counting of leaf comparisons is deliberately
 * <b>not</b> part of this carrier: the counting samples and their counters belong to the core test
 * side, so the benchmark reuses the sample shapes only and keeps counting runs apart from the timed
 * runs.
 * <p>
 * <b>Precondition and measured operation.</b> The fixture of one iteration holds the graph sample of
 * the scanned topology, the tracker assembled through the api facade and the baseline registration,
 * plus the scanned change level applied right after the assembly. {@code calculateChanges} is a side
 * effect free idempotent view, so no per invocation reset is declared and the measured call still runs
 * the whole comparison.
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
public class SharedSubgraphBenchmark {

    /** Logger of the iteration assembly; the assembly runs outside the measured region. */
    private static final Logger log = LoggerFactory.getLogger(SharedSubgraphBenchmark.class);

    /**
     * Shared scan state of the graph paths: it assembles the graph sample of the scanned topology and
     * applies the scanned change level to it.
     * <p>
     * The sample keeps its changed state for the whole iteration, because the measured comparison has
     * no side effect on it; a scan state of this path therefore declares no per invocation reset.
     * {@code @State(Scope.Thread)} is resolved through the state class hierarchy, so concrete scan
     * states carry no state annotation of their own.
     */
    @State(Scope.Thread)
    public abstract static class GraphScanState {

        /** No change level: the sample stays identical to its tracking baseline. */
        public static final int NO_CHANGE_LEVEL = 0;

        /** Single change level: exactly one layer value of the graph root changes. */
        public static final int ROOT_VALUE_CHANGE_LEVEL = 1;

        /** Sample of the current iteration, created by the frozen sample family. */
        private Object sample;

        /** Tracker of the current iteration, assembled through the api facade. */
        private ChangeTracker tracker;

        /**
         * Assembles the iteration fixture: the graph sample of the scanned topology through the frozen
         * sample family, the tracker assembled through the api facade, the baseline registration, and
         * the scanned change level applied right after the assembly. This hook runs at iteration level,
         * outside the measured region.
         */
        @Setup(Level.Iteration)
        public void setUpIteration() {
            final SampleGraphNode graph = graph();
            final ChangeTracker iterationTracker = ChangeTrackerFactory.builder().withDefaults().build();
            iterationTracker.track(graph);
            this.sample = graph;
            this.tracker = iterationTracker;
            applyGraphChange(graph);
            log.info("Assembling the shared subgraph iteration fixture of {}", getClass().getSimpleName());
        }

        /**
         * Returns the sample of the current iteration.
         *
         * @return the graph sample built by the frozen sample family
         */
        public Object sample() {
            return this.sample;
        }

        /**
         * Returns the tracker of the current iteration.
         *
         * @return the tracker assembled through the api facade
         */
        public ChangeTracker tracker() {
            return this.tracker;
        }

        /**
         * Returns the graph sample of the scanned topology through the frozen sample family.
         *
         * @return the root node of the scanned graph
         */
        public abstract SampleGraphNode graph();

        /**
         * Applies the scanned change level to the sample: nothing at the no change level, one root
         * layer value at the single change level, delegated to
         * {@link com.nona.changeTracking.bench.sample.SampleMutator}.
         *
         * @param sample the sample assembled by the iteration fixture
         */
        public abstract void applyGraphChange(Object sample);
    }

    /**
     * Change level scan over the plain tree graph: no shared reference and no cycle, the regression
     * load of the reuse work.
     */
    public static class PlainTreeScanState extends GraphScanState {

        /** Scanned number of changed root layer values. */
        @Param({"0", "1"})
        public int plainTreeChangeLevel;

        /**
         * Returns the plain tree graph of the frozen depth: {@code 2 * depth + 1} independent objects
         * and no sharing.
         *
         * @return the root node of the plain tree sample
         */
        @Override
        public SampleGraphNode graph() {
            return SampleFamily.createPlainGraph(SampleFamily.GRAPH_DEPTH);
        }

        /**
         * Applies the scanned change level to the plain tree sample.
         *
         * @param sample the sample assembled by the iteration fixture
         */
        @Override
        public void applyGraphChange(final Object sample) {
            switch (this.plainTreeChangeLevel) {
                case NO_CHANGE_LEVEL -> {
                    // no change at this level
                }
                case ROOT_VALUE_CHANGE_LEVEL -> SampleMutator.changeGraphRootValue(sample);
                default -> throw new IllegalArgumentException(
                        "Unsupported plain tree change level: " + this.plainTreeChangeLevel);
            }
        }
    }

    /**
     * Change level scan over the shared subgraph: every branch references the same child twice, the
     * shape whose reachable paths grow exponentially while the independent objects grow linearly.
     */
    public static class SharedSubgraphScanState extends GraphScanState {

        /** Scanned number of changed root layer values. */
        @Param({"0", "1"})
        public int sharedSubgraphChangeLevel;

        /**
         * Returns the shared graph of the frozen depth: two references to the same child per branch
         * level and one terminal leaf.
         *
         * @return the root node of the shared graph sample
         */
        @Override
        public SampleGraphNode graph() {
            return SampleFamily.createSharedGraph(SampleFamily.GRAPH_DEPTH);
        }

        /**
         * Applies the scanned change level to the shared graph sample.
         *
         * @param sample the sample assembled by the iteration fixture
         */
        @Override
        public void applyGraphChange(final Object sample) {
            switch (this.sharedSubgraphChangeLevel) {
                case NO_CHANGE_LEVEL -> {
                    // no change at this level
                }
                case ROOT_VALUE_CHANGE_LEVEL -> SampleMutator.changeGraphRootValue(sample);
                default -> throw new IllegalArgumentException(
                        "Unsupported shared subgraph change level: " + this.sharedSubgraphChangeLevel);
            }
        }
    }

    /**
     * Change level scan over the cyclic graph: the comparison must terminate through the existing
     * cycle truncation while the cycle stays a cycle.
     */
    public static class CyclicGraphScanState extends GraphScanState {

        /** Scanned number of changed root layer values. */
        @Param({"0", "1"})
        public int cyclicGraphChangeLevel;

        /**
         * Returns the cycle of the frozen depth: every element references the next one and the last
         * element closes the cycle on the head.
         *
         * @return the head of the cyclic graph sample
         */
        @Override
        public SampleGraphNode graph() {
            return SampleFamily.createCyclicGraph(SampleFamily.GRAPH_DEPTH);
        }

        /**
         * Applies the scanned change level to the cyclic graph sample.
         *
         * @param sample the sample assembled by the iteration fixture
         */
        @Override
        public void applyGraphChange(final Object sample) {
            switch (this.cyclicGraphChangeLevel) {
                case NO_CHANGE_LEVEL -> {
                    // no change at this level
                }
                case ROOT_VALUE_CHANGE_LEVEL -> SampleMutator.changeGraphRootValue(sample);
                default -> throw new IllegalArgumentException(
                        "Unsupported cyclic graph change level: " + this.cyclicGraphChangeLevel);
            }
        }
    }

    /**
     * Change level scan over the mixed graph: a shared subgraph and a cycle in one object graph.
     */
    public static class MixedGraphScanState extends GraphScanState {

        /** Scanned number of changed root layer values. */
        @Param({"0", "1"})
        public int mixedGraphChangeLevel;

        /**
         * Returns the mixed graph of the frozen depth: one root branch referencing a shared subgraph
         * and a cycle.
         *
         * @return the root node of the mixed graph sample
         */
        @Override
        public SampleGraphNode graph() {
            return SampleFamily.createMixedGraph(SampleFamily.GRAPH_DEPTH);
        }

        /**
         * Applies the scanned change level to the mixed graph sample.
         *
         * @param sample the sample assembled by the iteration fixture
         */
        @Override
        public void applyGraphChange(final Object sample) {
            switch (this.mixedGraphChangeLevel) {
                case NO_CHANGE_LEVEL -> {
                    // no change at this level
                }
                case ROOT_VALUE_CHANGE_LEVEL -> SampleMutator.changeGraphRootValue(sample);
                default -> throw new IllegalArgumentException(
                        "Unsupported mixed graph change level: " + this.mixedGraphChangeLevel);
            }
        }
    }

    /**
     * Measures the change detection of the plain tree sample for one change level.
     *
     * @param state     scan state holding the fixture and its change level
     * @param blackhole consumer of the computed change set
     */
    @Benchmark
    public void calculateChangesByPlainTreeChangeLevel(final PlainTreeScanState state, final Blackhole blackhole) {
        Objects.requireNonNull(state, "state");
        blackhole.consume(state.tracker().calculateChanges());
    }

    /**
     * Measures the change detection of the shared subgraph sample for one change level.
     *
     * @param state     scan state holding the fixture and its change level
     * @param blackhole consumer of the computed change set
     */
    @Benchmark
    public void calculateChangesBySharedSubgraphChangeLevel(final SharedSubgraphScanState state,
                                                            final Blackhole blackhole) {
        Objects.requireNonNull(state, "state");
        blackhole.consume(state.tracker().calculateChanges());
    }

    /**
     * Measures the change detection of the cyclic graph sample for one change level.
     *
     * @param state     scan state holding the fixture and its change level
     * @param blackhole consumer of the computed change set
     */
    @Benchmark
    public void calculateChangesByCyclicGraphChangeLevel(final CyclicGraphScanState state,
                                                         final Blackhole blackhole) {
        Objects.requireNonNull(state, "state");
        blackhole.consume(state.tracker().calculateChanges());
    }

    /**
     * Measures the change detection of the mixed graph sample for one change level.
     *
     * @param state     scan state holding the fixture and its change level
     * @param blackhole consumer of the computed change set
     */
    @Benchmark
    public void calculateChangesByMixedGraphChangeLevel(final MixedGraphScanState state,
                                                        final Blackhole blackhole) {
        Objects.requireNonNull(state, "state");
        blackhole.consume(state.tracker().calculateChanges());
    }
}
