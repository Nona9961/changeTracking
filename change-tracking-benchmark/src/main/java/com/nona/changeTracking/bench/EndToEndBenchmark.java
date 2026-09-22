package com.nona.changeTracking.bench;

import com.nona.changeTracking.bench.sample.SampleFamily;
import com.nona.changeTracking.bench.sample.SampleMutator;
import com.nona.changeTracking.bench.sample.SampleOrder;
import com.nona.changeTracking.bench.sample.SampleOrderSummary;
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

import java.util.concurrent.TimeUnit;

/**
 * Benchmark of the end to end path, {@code track -> modification -> calculateChanges}, scanned over
 * the field count, nesting depth and collection size dimensions of the frozen sample family.
 * <p>
 * <b>Measured operation</b>, identical for every measured method and equal to the change detection
 * cycle the library documents (read, track, mutate, calculate changes before saving):
 * <ol>
 *   <li>{@code tracker().track(sample())} - the sample is untracked after the precondition reset, so
 *       this call dehydrates the whole sample and rebuilds the baseline instead of taking the
 *       idempotent early return of {@link com.nona.changeTracking.domain.model.tracking.ChangeTracker#track(Object)}.</li>
 *   <li>{@code SampleMutator.addItem(sample())} - one in place append of a line item: the collection
 *       instance stays the same, the appended item is created by the frozen sample family, and that
 *       single item allocation belongs to the measured operation, so it is accounted to the time and
 *       the allocation metric on purpose.</li>
 *   <li>{@code tracker().calculateChanges()} - dehydrates the current state and compares it with the
 *       baseline, producing a change set holding the appended item, which is consumed through the
 *       {@link Blackhole} so dead code elimination cannot remove the measured path.</li>
 * </ol>
 * <b>Precondition reset</b>: {@code @Setup(Level.Invocation)} on {@link FullPathScanState}, because a
 * repeated call without a reset would not measure the path at all - {@code track} returns early for
 * the sample the iteration fixture registered, and {@code calculateChanges} is a side effect free view
 * that never advances the baseline. The reset has to reach both consumed preconditions:
 * <ul>
 *   <li><b>untracked</b>: {@code ChangeTracker.stopTracking} removes the sample from the tracking set,
 *       which only clears the tracking state and leaves the field values alone.</li>
 *   <li><b>iteration initial collection</b>: the reset restores the item collection to the size it had
 *       when the iteration fixture was built, which also restores the item order and the item
 *       instances, because the measured append is appended at the end. Without that restoration the
 *       collection would grow by one item per call and the collection size dimension would drift.</li>
 * </ul>
 * The reset is a target state reset: it converges to the iteration initial state, so it is a no-op
 * before the first measured call, repeated resets are idempotent, and it never rebuilds the iteration
 * fixture (sample and tracker are reused) and never allocates. Rebuilding the fixture per invocation
 * is ruled out on purpose: the rebuilding allocation of the whole sample tree would be divided by the
 * operation count into {@code gc.alloc.rate.norm} and would misattribute the rebuilding cost to the
 * measured path. The reset target is taken from the scanned shape while the iteration fixture is
 * assembled and cached in a plain {@code int} field, because a per-invocation reset runs inside the
 * measured window and every allocation it performs - including the allocation of a
 * {@link SampleShape} record or of a rebuilt collection - would be divided into
 * {@code gc.alloc.rate.norm}; the cached target is stable across the iterations of one benchmark
 * entry, because every iteration of that entry uses the same scan level.
 * <p>
 * <b>Frozen parameterization pattern</b>, reused from {@link SnapshotBuildBenchmark}: one benchmark
 * class per measured path in this package named {@code <Path>Benchmark} with the module annotation
 * combination ({@code @BenchmarkMode(AverageTime)}, {@code @OutputTimeUnit(MICROSECONDS)},
 * {@code @Warmup(iterations = 3, time = 1)}, {@code @Measurement(iterations = 5, time = 1)},
 * {@code @Fork(1)}, {@code @State(Scope.Thread)}); one {@code public static} scan state class per
 * scanned dimension extending {@link DimensionScanState} with exactly one {@code public int}
 * {@code @Param} field whose levels are the frozen levels of that dimension and whose remaining
 * dimensions stay at {@link SampleShape#defaults()}; one scan state class per {@code @Benchmark}
 * method named {@code <operation>By<dimension>} taking that state class plus a {@link Blackhole};
 * samples built only through {@link SampleFamily#create(SampleShape)} by the iteration level fixture
 * of the shared base class.
 * <p>
 * <b>Logging</b>: the measured bodies and the per-invocation reset hook are executed once per
 * invocation, so they carry no log entry - it would be part of the measured region. The visible
 * logging of a run comes from the iteration level assembly of
 * {@link DimensionScanState#setUpIteration()}.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class EndToEndBenchmark {

    /**
     * Scan state of the end to end path: it adds the per-invocation precondition reset to the shared
     * iteration fixture.
     * <p>
     * The reset restores the two preconditions the measured operation consumes - the untracked state
     * of the sample and the iteration initial item collection - without rebuilding the sample or the
     * tracker and without allocating, so the next measured call runs the whole path from the same
     * starting point as the first one.
     */
    public abstract static class FullPathScanState extends DimensionScanState {

        /** Item count of the iteration initial collection, the target the reset restores. */
        private int iterationItemCount;

        /**
         * Caches the reset target of this scan level and returns the scanned shape unchanged.
         * <p>
         * The scanned shape is derived at iteration level by {@link DimensionScanState#setUpIteration()},
         * so caching the collection size of the shape here is the allocation free way to know how many
         * items the iteration fixture holds: the per-invocation reset only compares against the cached
         * target instead of deriving it inside the measured window. Every iteration of one benchmark
         * entry uses the same scan level, so the cached target stays valid for all iterations of that
         * entry.
         *
         * @param scanShape the shape of this scan level, built by the concrete scan state
         * @return the scanned shape
         */
        protected final SampleShape shapeCarryingResetTarget(final SampleShape scanShape) {
            this.iterationItemCount = scanShape.collectionSize();
            return scanShape;
        }

        /**
         * Restores the preconditions of the measured call: it removes the sample from the tracking
         * set and returns the item collection to the size of the iteration initial state, which
         * restores the appended item as well.
         * <p>
         * The reset is a target state reset: it is a no-op while the sample still holds the iteration
         * initial collection, so it is safe before the first measured call, and repeated resets are
         * idempotent. The target size is read from the iteration level cache, never derived here,
         * because this hook runs inside the measured window and every allocation it performs would be
         * divided into {@code gc.alloc.rate.norm}. Both the target state comparison and the removal of
         * the appended item are in place operations on the sample built by the iteration fixture: the
         * reset must not rebuild the sample or the tracker and must not allocate. Removing the
         * appended item is the last element removal of the {@code ArrayList} the sample family built,
         * so the reset never rebuilds the collection.
         */
        @Setup(Level.Invocation)
        public void resetPrecondition() {
            final Object sample = sample();
            tracker().stopTracking(sample);
            int size = itemCountOf(sample);
            while (size > iterationItemCount) {
                SampleMutator.removeItem(sample, size - 1);
                size--;
            }
        }

        /**
         * Returns the item count of a sample root of the frozen sample family.
         *
         * @param sample a sample root created by {@link SampleFamily#create(SampleShape)}
         * @return the current number of items held by the sample root
         * @throws IllegalStateException if the sample is not a sample root of this family
         */
        private static int itemCountOf(final Object sample) {
            if (sample instanceof SampleOrder order) {
                return order.items().size();
            }
            if (sample instanceof SampleOrderSummary summary) {
                return summary.items().size();
            }
            throw new IllegalStateException("Unsupported sample type: " + sample.getClass().getName());
        }
    }

    /**
     * Field count scan: 5 and 20 scalar fields, every other dimension at its default.
     */
    public static class FieldCountScan extends FullPathScanState {

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
            return shapeCarryingResetTarget(SampleShape.of(fieldCount, SampleShape.DEFAULT_NESTING_DEPTH,
                    SampleShape.DEFAULT_COLLECTION_SIZE));
        }
    }

    /**
     * Nesting depth scan: address chain depths 1 to 5, every other dimension at its default.
     */
    public static class NestingDepthScan extends FullPathScanState {

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
            return shapeCarryingResetTarget(SampleShape.of(SampleShape.DEFAULT_FIELD_COUNT, nestingDepth,
                    SampleShape.DEFAULT_COLLECTION_SIZE));
        }
    }

    /**
     * Collection size scan: item counts 10, 100 and 1000, every other dimension at its default.
     */
    public static class CollectionSizeScan extends FullPathScanState {

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
            return shapeCarryingResetTarget(SampleShape.of(SampleShape.DEFAULT_FIELD_COUNT,
                    SampleShape.DEFAULT_NESTING_DEPTH, collectionSize));
        }
    }

    /**
     * Runs the whole path of one field count level: track the sample, append one item in place and
     * calculate the changes, consumed through the blackhole.
     *
     * @param state     scan state holding the fixture and the precondition reset of the invocation
     * @param blackhole consumer of the computed change set
     */
    @Benchmark
    public void trackAndDiffByFieldCount(final FieldCountScan state, final Blackhole blackhole) {
        blackhole.consume(runWholePath(state));
    }

    /**
     * Runs the whole path of one nesting depth level: track the sample, append one item in place and
     * calculate the changes, consumed through the blackhole.
     *
     * @param state     scan state holding the fixture and the precondition reset of the invocation
     * @param blackhole consumer of the computed change set
     */
    @Benchmark
    public void trackAndDiffByNestingDepth(final NestingDepthScan state, final Blackhole blackhole) {
        blackhole.consume(runWholePath(state));
    }

    /**
     * Runs the whole path of one collection size level: track the sample, append one item in place
     * and calculate the changes, consumed through the blackhole.
     *
     * @param state     scan state holding the fixture and the precondition reset of the invocation
     * @param blackhole consumer of the computed change set
     */
    @Benchmark
    public void trackAndDiffByCollectionSize(final CollectionSizeScan state, final Blackhole blackhole) {
        blackhole.consume(runWholePath(state));
    }

    /**
     * Runs the measured operation on the iteration fixture: track the sample to rebuild the baseline
     * from its current state, append one item in place and calculate the changes against that
     * baseline.
     *
     * @param state scan state holding the fixture of the current invocation
     * @return the change set of the appended item
     */
    private static ChangeSet runWholePath(final DimensionScanState state) {
        final Object sample = state.sample();
        state.tracker().track(sample);
        SampleMutator.addItem(sample);
        return state.tracker().calculateChanges();
    }
}
