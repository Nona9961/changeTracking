package com.nona.changeTracking.bench;

import com.nona.changeTracking.bench.sample.SampleFamily;
import com.nona.changeTracking.bench.sample.SampleShape;
import com.nona.changeTracking.domain.capability.TrackingCapability;
import com.nona.changeTracking.domain.model.tracking.BaselineSnapshot;
import com.nona.changeTracking.domain.model.tracking.ChangeTracker;
import com.nona.changeTracking.spi.TrackingCapabilityProvider;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.concurrent.TimeUnit;

/**
 * Steady state measurement of the three warm type processing cache states: reuse on the same
 * capability, a new tracker reusing that capability, and a new tracker with a new capability.
 * <p>
 * <b>States</b> (performance acceptance method): the steady state protocol warms the capability first and then
 * measures the reuse paths, so every measured method of this class starts from at least one warm
 * cache level:
 * <ul>
 *   <li><b>Same capability reuse</b> - the iteration fixture holds a capability whose class level and
 *       configuration level caches were warmed by the assembly snapshot; the snapshot method measures
 *       the steady state snapshot build, the calculation method measures the steady state complete
 *       calculation on the same capability.</li>
 *   <li><b>New tracker, reused capability</b> - the measured operation creates a tracker on the
 *       warmed capability and calculates the changes of the restored baseline; the configuration
 *       level cache of that capability is still warm, because it belongs to the capability.</li>
 *   <li><b>New tracker, new capability</b> - the measured operation creates a capability and a
 *       tracker; the class level metadata is warm (the assembly warmed it with a separate instance of
 *       the same configuration) while the configuration level cache of the new capability is cold,
 *       which is the class level warm, new instance configuration level cold state.</li>
 * </ul>
 * <p>
 * <b>Preconditions.</b> The snapshot method consumes its precondition ({@code track} keeps the sample
 * identity in the tracking set and returns early afterwards), so its state declares a per invocation
 * reset that restores the untracked precondition allocation free through
 * {@code ChangeTracker.stopTracking}. The calculation methods consume no precondition: their
 * iteration fixture already holds the baseline of the sample, and {@code calculateChanges} is a side
 * effect free view that neither advances the baseline nor modifies the sample, so a per invocation
 * reset would only add assembly work to {@code gc.alloc.rate.norm}. Both new tracker methods record
 * the tracker they created (and the new capability method also the capability), so the unit test can
 * verify that they are created per invocation instead of being reused from the assembly.
 * <p>
 * <b>Frozen parameterization pattern.</b> Class level annotations identical to the module smoke
 * benchmark ({@code @BenchmarkMode(AverageTime)}, {@code @OutputTimeUnit(MICROSECONDS)},
 * {@code @Warmup(iterations = 3, time = 1)}, {@code @Measurement(iterations = 5, time = 1)},
 * {@code @Fork(1)}, {@code @State(Scope.Thread)}); one state class per measured operation, each state
 * carrying its own assembly hook and, when its operation consumes a precondition, its own per
 * invocation reset; samples built only through the frozen sample family and baselines only through
 * {@link ColdBaselineFixture}, so no measurement of this class builds a sample or a node tree on its
 * own; results consumed through the blackhole.
 * <p>
 * <b>Logging.</b> Logging belongs to the iteration level assembly; the measured bodies and the per
 * invocation reset carry no log entry, because they run once per invocation.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class CacheStateBenchmark {

    /** Provider name the documented selection strategy prefers, the same name the facade prefers. */
    public static final String DEFAULT_PROVIDER_NAME = "default-reflection";

    /** Logger of the iteration assembly; the assembly runs outside the measured region. */
    private static final Logger log = LoggerFactory.getLogger(CacheStateBenchmark.class);

    /**
     * State of the steady state snapshot measurement on a reused capability: the iteration fixture
     * holds the sample and a tracker whose capability was warmed by the assembly snapshot, and the
     * per invocation reset restores the untracked precondition without allocating.
     */
    @State(Scope.Thread)
    public static class ReusedCapabilitySnapshotState {

        /** Sample of the current iteration. */
        private Object sample;

        /** Tracker of the current iteration, assembled on the warmed capability. */
        private ChangeTracker tracker;

        /**
         * Assembles the iteration fixture: the default sample of the frozen sample family and one
         * tracker assembled through the public extension point, whose baseline registration warms the
         * class level and the configuration level cache of its capability.
         */
        @Setup(Level.Iteration)
        public void setUpIteration() {
            this.sample = SampleFamily.create(SampleShape.defaults());
            this.tracker = new ChangeTracker(capabilityProvider().create());
            this.tracker.track(this.sample);
            log.info("Iteration fixture assembled for {}: the reused capability is warm",
                    getClass().getSimpleName());
        }

        /**
         * Restores the untracked precondition of the measured snapshot allocation free, leaving the
         * sample and the tracker of the iteration in place.
         */
        @Setup(Level.Invocation)
        public void resetPrecondition() {
            this.tracker.stopTracking(this.sample);
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
         * @return the tracker carrying the warmed capability
         */
        public ChangeTracker tracker() {
            return this.tracker;
        }
    }

    /**
     * State of the steady state complete calculation measurement on a reused capability: the
     * iteration fixture holds the sample with its baseline registered, so the measured calculation
     * runs with both cache levels warm and needs no per invocation reset.
     */
    @State(Scope.Thread)
    public static class ReusedCapabilityCalculationState {

        /** Sample of the current iteration, already tracked by the iteration tracker. */
        private Object sample;

        /** Tracker of the current iteration, carrying the registered baseline. */
        private ChangeTracker tracker;

        /**
         * Assembles the iteration fixture: the default sample of the frozen sample family and one
         * tracker whose baseline registration warms both cache levels of its capability.
         */
        @Setup(Level.Iteration)
        public void setUpIteration() {
            this.sample = SampleFamily.create(SampleShape.defaults());
            this.tracker = new ChangeTracker(capabilityProvider().create());
            this.tracker.track(this.sample);
            log.info("Iteration fixture assembled for {}: both cache levels are warm",
                    getClass().getSimpleName());
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
         * @return the tracker carrying the warmed capability and the registered baseline
         */
        public ChangeTracker tracker() {
            return this.tracker;
        }
    }

    /**
     * State of the new tracker measurement on a reused capability: the iteration fixture holds the
     * sample, its hand built baseline and a capability warmed by the assembly snapshot, so the
     * measured invocation only creates the tracker and calculates.
     */
    @State(Scope.Thread)
    public static class ReusedCapabilityNewTrackerState {

        /** Sample of the current iteration, not tracked by the fixture. */
        private Object sample;

        /** Hand built baseline of the sample, built without calling the target strategy. */
        private BaselineSnapshot baseline;

        /** Capability of the current iteration, warmed by the assembly snapshot. */
        private TrackingCapability<?> capability;

        /** Tracker created by the last measured invocation, exposed for the assembly verification. */
        private ChangeTracker lastTracker;

        /**
         * Assembles the iteration fixture: the default sample, its hand built baseline and one warmed
         * capability whose configuration level cache belongs to this iteration and is therefore still
         * warm inside every measured invocation.
         */
        @Setup(Level.Iteration)
        public void setUpIteration() {
            this.sample = SampleFamily.create(SampleShape.defaults());
            this.baseline = ColdBaselineFixture.baselineOf(this.sample);
            this.capability = capabilityProvider().create();
            warmCapability(this.capability);
            log.info("Iteration fixture assembled for {}: the reused capability is warm",
                    getClass().getSimpleName());
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
         * Returns the hand built baseline of the iteration.
         *
         * @return the baseline built by {@link ColdBaselineFixture}
         */
        public BaselineSnapshot baseline() {
            return this.baseline;
        }

        /**
         * Returns the warmed capability of the iteration.
         *
         * @return the capability every measured invocation reuses
         */
        public TrackingCapability<?> capability() {
            return this.capability;
        }

        /**
         * Returns the tracker created by the last measured invocation.
         *
         * @return the tracker of the last measured invocation, null before the first one
         */
        public ChangeTracker lastTracker() {
            return this.lastTracker;
        }
    }

    /**
     * State of the new capability measurement: the iteration fixture holds the sample, its hand built
     * baseline, the provider that creates the capabilities and one throwaway capability of the same
     * configuration, which warms the shared class metadata without warming the configuration level
     * cache of the capabilities created inside the measured region.
     */
    @State(Scope.Thread)
    public static class NewCapabilityState {

        /** Sample of the current iteration, not tracked by the fixture. */
        private Object sample;

        /** Hand built baseline of the sample, built without calling the target strategy. */
        private BaselineSnapshot baseline;

        /** Provider creating one capability per measured invocation. */
        private TrackingCapabilityProvider provider;

        /** Capability created by the last measured invocation, exposed for the assembly verification. */
        private TrackingCapability<?> lastCapability;

        /** Tracker created by the last measured invocation, exposed for the assembly verification. */
        private ChangeTracker lastTracker;

        /**
         * Assembles the iteration fixture: the default sample, its hand built baseline, the provider
         * discovered through the public extension point and one throwaway capability of the same
         * configuration used to warm the shared class metadata only.
         */
        @Setup(Level.Iteration)
        public void setUpIteration() {
            this.sample = SampleFamily.create(SampleShape.defaults());
            this.baseline = ColdBaselineFixture.baselineOf(this.sample);
            this.provider = capabilityProvider();
            warmCapability(this.provider.create());
            log.info("Iteration fixture assembled for {}: the shared class metadata is warm",
                    getClass().getSimpleName());
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
         * Returns the hand built baseline of the iteration.
         *
         * @return the baseline built by {@link ColdBaselineFixture}
         */
        public BaselineSnapshot baseline() {
            return this.baseline;
        }

        /**
         * Returns the capability created by the last measured invocation.
         *
         * @return the capability of the last measured invocation, null before the first one
         */
        public TrackingCapability<?> lastCapability() {
            return this.lastCapability;
        }

        /**
         * Returns the tracker created by the last measured invocation.
         *
         * @return the tracker of the last measured invocation, null before the first one
         */
        public ChangeTracker lastTracker() {
            return this.lastTracker;
        }
    }

    /**
     * Measures one steady state snapshot build on the warmed capability of the iteration.
     *
     * @param state     state holding the fixture and the per invocation precondition reset
     * @param blackhole consumer of the produced snapshot
     */
    @Benchmark
    public void snapshotByReusedCapability(final ReusedCapabilitySnapshotState state, final Blackhole blackhole) {
        Objects.requireNonNull(state, "state");
        final ChangeTracker tracker = state.tracker();
        tracker.track(state.sample());
        blackhole.consume(tracker);
    }

    /**
     * Measures one steady state complete calculation on the warmed capability of the iteration.
     *
     * @param state     state holding the fixture with the registered baseline
     * @param blackhole consumer of the computed change set
     */
    @Benchmark
    public void calculateChangesByReusedCapability(final ReusedCapabilityCalculationState state,
                                                   final Blackhole blackhole) {
        Objects.requireNonNull(state, "state");
        blackhole.consume(state.tracker().calculateChanges());
    }

    /**
     * Measures the complete calculation of a tracker created per invocation on the capability reused
     * from the iteration, including the tracker creation on the restored baseline.
     *
     * @param state     state holding the fixture, its baseline and the reused capability
     * @param blackhole consumer of the computed change set
     */
    @Benchmark
    public void calculateChangesByNewTrackerReusingCapability(final ReusedCapabilityNewTrackerState state,
                                                              final Blackhole blackhole) {
        Objects.requireNonNull(state, "state");
        final ChangeTracker tracker = ChangeTracker.fromBaseline(state.capability(), state.baseline());
        state.lastTracker = tracker;
        blackhole.consume(tracker.calculateChanges());
    }

    /**
     * Measures the complete calculation of a tracker and a capability created per invocation, with the
     * shared class metadata warm and the new configuration level cache cold.
     *
     * @param state     state holding the fixture, its baseline and the provider
     * @param blackhole consumer of the computed change set
     */
    @Benchmark
    public void calculateChangesByNewTrackerAndCapability(final NewCapabilityState state,
                                                          final Blackhole blackhole) {
        Objects.requireNonNull(state, "state");
        final TrackingCapability<?> capability = state.provider.create();
        final ChangeTracker tracker = ChangeTracker.fromBaseline(capability, state.baseline());
        state.lastCapability = capability;
        state.lastTracker = tracker;
        blackhole.consume(tracker.calculateChanges());
    }

    /**
     * Warms the class level and the configuration level cache of a capability with one throwaway
     * snapshot, so a measured invocation starts from a warm shared metadata and a warm capability
     * without measuring the warmup itself.
     *
     * @param capability the capability to warm
     */
    private static void warmCapability(final TrackingCapability<?> capability) {
        final ChangeTracker warmer = new ChangeTracker(capability);
        warmer.track(SampleFamily.create(SampleShape.defaults()));
    }

    /**
     * Creates a capability provider through the public extension point, repeating the documented
     * selection strategy of the facade: the preferred provider name when present, otherwise the
     * alphabetically first name.
     *
     * @return a provider that creates one capability per call, never null
     * @throws IllegalStateException if no provider is visible to the caller
     */
    static TrackingCapabilityProvider capabilityProvider() {
        final Map<String, TrackingCapabilityProvider> providers = new HashMap<>();
        for (final TrackingCapabilityProvider provider : ServiceLoader.load(TrackingCapabilityProvider.class)) {
            providers.put(provider.getName(), provider);
        }
        if (providers.isEmpty()) {
            throw new IllegalStateException("No TrackingCapabilityProviders found. "
                    + "Ensure at least one is available via ServiceLoader.");
        }
        final String selectedName = providers.containsKey(DEFAULT_PROVIDER_NAME)
                ? DEFAULT_PROVIDER_NAME
                : Collections.min(providers.keySet());
        return providers.get(selectedName);
    }
}