package com.nona.changeTracking.bench;

import com.nona.changeTracking.bench.sample.SampleFamily;
import com.nona.changeTracking.bench.sample.SampleLineItem;
import com.nona.changeTracking.bench.sample.SampleMutator;
import com.nona.changeTracking.bench.sample.SampleShape;
import com.nona.changeTracking.domain.model.tracking.ChangeTracker;
import com.nona.changeTracking.spi.TrackingCapabilityProvider;
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
 * Collection path benchmark of the comparison strategy (T02, US01/US05).
 * <p>
 * <b>Scan axis.</b> The collection change shape is scanned with everything else frozen: no change,
 * value replacement, addition and removal, and reorder. The state registers the business identifier
 * of the sample family on its capability, so matching uses the business identifier and the change
 * paths format that identifier text — this is the load whose matching and identifier formatting cost
 * the ordered match index and the on-demand path of US01 and US05 measure together.
 * <p>
 * <b>Precondition and measured operation.</b> The iteration fixture holds the sample, the tracker of
 * the identifier configuration and the baseline registration, plus the scanned change level applied
 * right after the assembly. {@code calculateChanges} is a side effect free idempotent view, so the
 * measured call still runs the whole comparison and the sample keeps its changed state.
 * <p>
 * <b>Frozen parameterization pattern.</b> Class level annotations identical to the module smoke
 * benchmark, one state class carrying the assembly hook, one {@code @Param} dimension, samples built
 * only through the frozen sample family, results consumed through the blackhole.
 * <p>
 * <b>Logging.</b> Logging belongs to the iteration level assembly; the measured body carries no log
 * entry, because it runs once per invocation.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class CollectionPathBenchmark {

    /** Logger of the iteration assembly; the assembly runs outside the measured region. */
    private static final Logger log = LoggerFactory.getLogger(CollectionPathBenchmark.class);

    /**
     * State of the collection path load: it assembles the sample of the default frozen shape, the
     * tracker of the business identifier configuration and applies the scanned collection change
     * level.
     */
    @State(Scope.Thread)
    public static class CollectionIdentifierPathState {

        /** No change level: the collection stays identical to its tracking baseline. */
        public static final int NO_CHANGE_LEVEL = 0;

        /** Value replacement level: the payload of one existing item changes. */
        public static final int VALUE_REPLACEMENT_LEVEL = 1;

        /** Addition and removal level: one item is added and one item is removed. */
        public static final int ADDITION_AND_REMOVAL_LEVEL = 2;

        /** Reorder level: the collection is reordered in place without changing its items. */
        public static final int REORDER_LEVEL = 3;

        /** Index of the item replaced and of the item removed by the collection change levels. */
        private static final int FIRST_ITEM_INDEX = 0;

        /** Scanned collection change level. */
        @Param({"0", "1", "2", "3"})
        public int collectionChangeShape;

        /** Sample of the current iteration. */
        private Object sample;

        /** Tracker of the current iteration, assembled on the business identifier configuration. */
        private ChangeTracker tracker;

        /**
         * Assembles the iteration fixture: the default sample of the frozen sample family, the tracker
         * of the capability carrying the sample business identifier, the baseline registration, and the
         * scanned collection change level applied right after the assembly. This hook runs at iteration
         * level, outside the measured region.
         */
        @Setup(Level.Iteration)
        public void setUpIteration() {
            this.sample = SampleFamily.create(SampleShape.defaults());
            final TrackingCapabilityProvider provider = CacheStateBenchmark.capabilityProvider();
            provider.withIdentifier(SampleLineItem.class, SampleLineItem::id);
            this.tracker = new ChangeTracker(provider.create());
            this.tracker.track(this.sample);
            applyCollectionChange(this.sample);
            log.info("Assembling the collection path iteration fixture of {}", getClass().getSimpleName());
        }

        /**
         * Returns the sample of the current iteration.
         *
         * @return the sample built by the frozen sample family
         */
        public Object sample() {
            return this.sample;
        }

        /**
         * Returns the tracker of the current iteration.
         *
         * @return the tracker carrying the business identifier configuration
         */
        public ChangeTracker tracker() {
            return this.tracker;
        }

        /**
         * Returns the shape both samples of the iteration are built with.
         *
         * @return the default shape of the frozen sample family
         */
        public SampleShape shape() {
            return SampleShape.defaults();
        }

        /**
         * Applies the scanned collection change level to the sample: the payload of one existing item
         * is replaced, one item is added and one removed, the collection is reordered in place, or
         * nothing at the no change level. Every level is delegated to an existing in place operation of
         * {@link com.nona.changeTracking.bench.sample.SampleMutator}.
         *
         * @param sample the sample assembled by the iteration fixture
         */
        public void applyCollectionChange(final Object sample) {
            switch (this.collectionChangeShape) {
                case NO_CHANGE_LEVEL -> {
                    // 零变更档位：集合保持与追踪基线一致。
                }
                case VALUE_REPLACEMENT_LEVEL -> SampleMutator.replaceItem(sample, FIRST_ITEM_INDEX);
                case ADDITION_AND_REMOVAL_LEVEL -> {
                    SampleMutator.addItem(sample);
                    SampleMutator.removeItem(sample, FIRST_ITEM_INDEX);
                }
                case REORDER_LEVEL -> SampleMutator.reorderItems(sample);
                default -> throw new IllegalArgumentException(
                        "Unsupported collectionChangeShape: " + this.collectionChangeShape);
            }
        }
    }

    /**
     * Measures the change detection of the collection path load for one scanned collection change
     * level.
     *
     * @param state     state holding the fixture and its collection change level
     * @param blackhole consumer of the computed change set
     */
    @Benchmark
    public void calculateChangesByCollectionChangeShape(final CollectionIdentifierPathState state,
                                                        final Blackhole blackhole) {
        Objects.requireNonNull(state, "state");
        blackhole.consume(state.tracker().calculateChanges());
    }
}
