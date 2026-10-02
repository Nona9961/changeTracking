package com.nona.changeTracking.bench;

import com.nona.changeTracking.api.ChangeTrackerFactory;
import com.nona.changeTracking.bench.sample.SampleFamily;
import com.nona.changeTracking.bench.sample.SampleLineItem;
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
 * Alternating use of two capabilities with different configurations: every measured invocation runs
 * the change detection cycle of the other configuration, so the measurement covers the load the SRS
 * names 多配置交替使用 rather than the cost of a single configuration.
 * <p>
 * <b>The two configurations.</b> The first capability is the unconfigured default capability assembled
 * through the api facade, the second one is assembled through the public extension point with one
 * business identifier registered on {@link SampleLineItem}, the identifier configuration the sample
 * family documents. Both iterations therefore carry the same sample shape but different type rule
 * configurations, which is exactly the load whose isolation the design requires: the rules of one
 * configuration must not leak into the other while both stay in use.
 * <p>
 * <b>Alternation semantics.</b> The per invocation reset of the state advances the selection and
 * restores the untracked precondition of the newly selected configuration allocation free; the
 * measured body then runs the cycle of the selected configuration. JMH invokes the reset of the
 * current invocation immediately before the measured body of the same invocation, so consecutive
 * invocations alternate between the two configurations and both cache sets stay in use across the
 * iteration.
 * <p>
 * <b>Measured operation.</b> {@code track} of the selected sample plus {@code calculateChanges} of the
 * selected tracker, consumed through the blackhole; the sample of the iteration stays unmodified, so
 * the calculation reports the zero change case and the measured cost is the snapshot build plus the
 * comparison of both configurations, not the assembly of a fixture.
 * <p>
 * <b>Frozen parameterization pattern.</b> Class level annotations identical to the module smoke
 * benchmark; one state class carrying the assembly hook and the per invocation reset; samples built
 * only through the frozen sample family; results consumed through the blackhole.
 * <p>
 * <b>Logging.</b> Logging belongs to the iteration level assembly; the measured body and the per
 * invocation reset carry no log entry, because they run once per invocation.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class ConfigurationAlternationBenchmark {

    /** Logger of the iteration assembly; the assembly runs outside the measured region. */
    private static final Logger log = LoggerFactory.getLogger(ConfigurationAlternationBenchmark.class);

    /**
     * State of the alternating configuration load: it assembles both configurations with one sample
     * each, advances the selection per invocation and restores the untracked precondition of the
     * selected sample allocation free.
     */
    @State(Scope.Thread)
    public static class AlternatingConfigurationState {

        /** Sample tracked by the unconfigured default capability. */
        private Object defaultSample;

        /** Sample tracked by the capability with the business identifier configuration. */
        private Object identifiedSample;

        /** Tracker of the unconfigured default configuration. */
        private ChangeTracker defaultTracker;

        /** Tracker of the business identifier configuration. */
        private ChangeTracker identifiedTracker;

        /** Selection of the current invocation; advanced by the per invocation reset. */
        private boolean defaultConfigurationSelected = true;

        /**
         * Assembles both configurations of the iteration: two samples of the default frozen shape, the
         * tracker of the unconfigured default capability assembled through the api facade, the tracker
         * of the identifier configuration assembled through the public extension point, and the
         * baseline registration of both samples, which warms the cache levels both configurations
         * measure on.
         */
        @Setup(Level.Iteration)
        public void setUpIteration() {
            this.defaultSample = SampleFamily.create(SampleShape.defaults());
            this.identifiedSample = SampleFamily.create(SampleShape.defaults());
            this.defaultTracker = ChangeTrackerFactory.builder().withDefaults().build();
            final TrackingCapabilityProvider provider = CacheStateBenchmark.capabilityProvider();
            provider.withIdentifier(SampleLineItem.class, SampleLineItem::id);
            this.identifiedTracker = new ChangeTracker(provider.create());
            this.defaultTracker.track(this.defaultSample);
            this.identifiedTracker.track(this.identifiedSample);
            this.defaultConfigurationSelected = true;
            log.info("Alternating iteration fixture assembled for {}: both configurations carry their own "
                    + "sample and baseline", getClass().getSimpleName());
        }

        /**
         * Advances the selection to the other configuration and restores the untracked precondition of
         * the newly selected sample, allocation free, so the measured body of the same invocation runs
         * the whole cycle of that configuration.
         */
        @Setup(Level.Invocation)
        public void resetPrecondition() {
            this.defaultConfigurationSelected = !this.defaultConfigurationSelected;
            selectedTracker().stopTracking(selectedSample());
        }

        /**
         * Returns the sample selected for the current invocation.
         *
         * @return the sample of the selected configuration
         */
        public Object selectedSample() {
            return this.defaultConfigurationSelected ? this.defaultSample : this.identifiedSample;
        }

        /**
         * Returns the tracker selected for the current invocation.
         *
         * @return the tracker of the selected configuration
         */
        public ChangeTracker selectedTracker() {
            return this.defaultConfigurationSelected ? this.defaultTracker : this.identifiedTracker;
        }

        /**
         * Tells whether the unconfigured default configuration is selected in the current invocation.
         *
         * @return true when the default configuration is selected
         */
        public boolean defaultConfigurationSelected() {
            return this.defaultConfigurationSelected;
        }

        /**
         * Returns the shape both samples of the iteration are built with.
         *
         * @return the default shape of the frozen sample family
         */
        public SampleShape shape() {
            return SampleShape.defaults();
        }
    }

    /**
     * Measures the change detection cycle of the configuration selected for this invocation: the
     * tracked cycle of the selected tracker over its own sample.
     *
     * @param state     state holding both configurations and the selection of the invocation
     * @param blackhole consumer of the computed change set
     */
    @Benchmark
    public void trackAndDiffAlternatingConfigurations(final AlternatingConfigurationState state,
                                                      final Blackhole blackhole) {
        Objects.requireNonNull(state, "state");
        final ChangeTracker tracker = state.selectedTracker();
        tracker.track(state.selectedSample());
        blackhole.consume(tracker.calculateChanges());
    }
}