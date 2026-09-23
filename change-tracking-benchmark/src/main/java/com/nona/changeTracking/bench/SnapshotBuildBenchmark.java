package com.nona.changeTracking.bench;

import com.nona.changeTracking.bench.sample.SampleFamily;
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

import java.util.concurrent.TimeUnit;

/**
 * Benchmark of the snapshot build path, {@code ChangeTracker.track}, scanned over the field count,
 * nesting depth and collection size dimensions of the frozen sample family.
 * <p>
 * <b>Frozen parameterization pattern</b>, reused by the diff, facade and end to end benchmarks:
 * <ul>
 *   <li><b>Class organization</b>: one benchmark class per measured path in this package, named
 *       {@code <Path>Benchmark}, carrying {@code @BenchmarkMode(AverageTime)},
 *       {@code @OutputTimeUnit(MICROSECONDS)}, {@code @Warmup(iterations = 3, time = 1)},
 *       {@code @Measurement(iterations = 5, time = 1)}, {@code @Fork(1)} and
 *       {@code @State(Scope.Thread)}, exactly as the module smoke benchmark does.</li>
 *   <li><b>Dimension scan declaration</b>: one nested {@code public static} scan state class per
 *       scanned dimension, extending {@link DimensionScanState} and carrying exactly one
 *       {@code public int} {@code @Param} field; its levels are the only levels the sample family
 *       or the coverage matrix documents for that dimension, and the remaining dimensions stay at
 *       {@link SampleShape#defaults()}. One scan state class maps to exactly one {@code @Benchmark}
 *       method named {@code <operation>By<dimension>}, taking that state class plus a
 *       {@link Blackhole}.</li>
 *   <li><b>Sample assembly</b>: samples are created only by {@link SampleFamily#create(SampleShape)}
 *       through {@link DimensionScanState#setUpIteration()} at iteration level, so benchmark tasks
 *       never build samples of their own and every iteration starts from a freshly built sample.</li>
 *   <li><b>Precondition reset</b>: an operation that consumes its precondition restores it per
 *       invocation in a {@code @Setup(Level.Invocation)} hook declared by the path specific scan
 *       state, and the restoration must not allocate; a fixture rebuilt per invocation is visible in
 *       {@code gc.alloc.rate.norm} and distorts the time metric through the extra collection load.</li>
 *   <li><b>Result consumption</b>: the measured method consumes its result, either by returning the
 *       computed value or through {@link Blackhole}, so dead code elimination cannot remove the
 *       measured operation.</li>
 * </ul>
 * <p>
 * When the measured method only consumes its precondition and computes nothing else, an execution
 * log must not stay in the measured body or in the reset hook: both run once per invocation, so a log
 * entry there would be part of the measured region. Logging belongs to trial and iteration level
 * assembly; this benchmark class takes its logging from {@link DimensionScanState#setUpIteration()}.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class SnapshotBuildBenchmark {

    /**
     * Scan state of the snapshot build path: it adds the per-invocation precondition reset to the
     * shared iteration fixture.
     * <p>
     * {@code track} keeps the sample identity in the tracker, so a repeated invocation without a
     * reset only measures the idempotent early return. The untracked precondition is restored
     * allocation free by {@code ChangeTracker.stopTracking}, which leaves the sample and the tracker
     * of the iteration in place.
     */
    public abstract static class TrackScanState extends DimensionScanState {

        /**
         * Returns the sample of the iteration to the untracked precondition, so the next measured
         * invocation builds a whole snapshot instead of taking the idempotent early return.
         */
        @Setup(Level.Invocation)
        public void resetPrecondition() {
            tracker().stopTracking(sample());
        }
    }

    /**
     * Field count scan: 5 and 20 scalar fields, every other dimension at its default.
     */
    public static class FieldCountScan extends TrackScanState {

        /** Scanned field count level. */
        @Param({"5", "20"})
        public int fieldCount;

        /**
         * Returns the scanned shape: the field count level with the default depth and collection
         * size.
         *
         * @return the shape of this scan level
         */
        @Override
        public SampleShape shape() {
            return SampleShape.of(fieldCount, SampleShape.DEFAULT_NESTING_DEPTH,
                    SampleShape.DEFAULT_COLLECTION_SIZE);
        }
    }

    /**
     * Nesting depth scan: address chain depths 1 to 5, every other dimension at its default.
     */
    public static class NestingDepthScan extends TrackScanState {

        /** Scanned nesting depth level. */
        @Param({"1", "2", "3", "4", "5"})
        public int nestingDepth;

        /**
         * Returns the scanned shape: the nesting depth level with the default field count and
         * collection size.
         *
         * @return the shape of this scan level
         */
        @Override
        public SampleShape shape() {
            return SampleShape.of(SampleShape.DEFAULT_FIELD_COUNT, nestingDepth,
                    SampleShape.DEFAULT_COLLECTION_SIZE);
        }
    }

    /**
     * Collection size scan: item counts 10, 100 and 1000, every other dimension at its default.
     */
    public static class CollectionSizeScan extends TrackScanState {

        /** Scanned collection size level. */
        @Param({"10", "100", "1000"})
        public int collectionSize;

        /**
         * Returns the scanned shape: the collection size level with the default field count and
         * depth.
         *
         * @return the shape of this scan level
         */
        @Override
        public SampleShape shape() {
            return SampleShape.of(SampleShape.DEFAULT_FIELD_COUNT, SampleShape.DEFAULT_NESTING_DEPTH,
                    collectionSize);
        }
    }

    /**
     * Tracks the sample of one field count level; the tracked state is consumed through the
     * blackhole, because the effect of {@code track} lives in the tracking set of the tracker.
     *
     * @param state     scan state holding the fixture of the current invocation
     * @param blackhole consumer of the tracked state
     */
    @Benchmark
    public void trackByFieldCount(final FieldCountScan state, final Blackhole blackhole) {
        state.tracker().track(state.sample());
        blackhole.consume(state.tracker());
    }

    /**
     * Tracks the sample of one nesting depth level; the tracked state is consumed through the
     * blackhole, because the effect of {@code track} lives in the tracking set of the tracker.
     *
     * @param state     scan state holding the fixture of the current invocation
     * @param blackhole consumer of the tracked state
     */
    @Benchmark
    public void trackByNestingDepth(final NestingDepthScan state, final Blackhole blackhole) {
        state.tracker().track(state.sample());
        blackhole.consume(state.tracker());
    }

    /**
     * Tracks the sample of one collection size level; the tracked state is consumed through the
     * blackhole, because the effect of {@code track} lives in the tracking set of the tracker.
     *
     * @param state     scan state holding the fixture of the current invocation
     * @param blackhole consumer of the tracked state
     */
    @Benchmark
    public void trackByCollectionSize(final CollectionSizeScan state, final Blackhole blackhole) {
        state.tracker().track(state.sample());
        blackhole.consume(state.tracker());
    }
}
