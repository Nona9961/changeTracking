package com.nona.changeTracking.bench;

import com.nona.changeTracking.api.ChangeTrackerFactory;
import com.nona.changeTracking.bench.sample.SampleFamily;
import com.nona.changeTracking.bench.sample.SampleShape;
import com.nona.changeTracking.domain.capability.TrackingCapability;
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
 * Benchmark of the facade overhead: the facade (api) assembly path and the direct core path are
 * measured as pairs, so the cost of the thin wrapper layer can be read as the difference inside a
 * pair.
 * <p>
 * <b>Measured pairs.</b> Two pairs, both on the frozen default sample shape
 * ({@link SampleShape#defaults()}), because the coverage matrix assigns the facade path a paired
 * comparison and no dimension scan:
 * <ul>
 *   <li>{@code facadeBuild} over {@code directBuild}: one complete assembly per invocation. The
 *       facade pair calls {@code ChangeTrackerFactory.builder().withDefaults().build()}, the direct
 *       pair repeats the same work by hand on the public extension point
 *       ({@code ServiceLoader.load(TrackingCapabilityProvider.class)}, the documented selection
 *       strategy, {@code create()} and {@code new ChangeTracker(capability)}). The difference is the
 *       facade wrapper layer itself.</li>
 *   <li>{@code facadeTrack} over {@code directTrack}: one {@code track} call per invocation on a
 *       tracker assembled at iteration level by the corresponding path. Both sides end up with the
 *       same {@link ChangeTracker} implementation and the same provider, so the difference is the
 *       facade built information only. The measured bodies of the pair are source equivalent, so the
 *       only remaining source of difference is the iteration level assembly path outside the measured
 *       region. Across runs the paired allocation per operation ({@code gc.alloc.rate.norm}) ranges
 *       from far below the reported errors to more than them and changes sign, so the difference is
 *       dominated by run to run variation and cannot be read as the facade wrapper cost.</li>
 * </ul>
 * <p>
 * <b>Discovery cost ownership.</b> The facade api exposes no entry point that accepts an already
 * discovered provider, so a cached provider can only be prepared on the direct side; caching it there
 * would move the whole discovery cost onto the facade side of the pair and make the comparison
 * asymmetric. The discovery therefore runs inside the measured region of both build methods, where it
 * is present on both sides and cancels out, and outside the measured region of both track methods,
 * where the iteration fixture assembled at {@link Level#Iteration} carries the once per iteration
 * assembly cost instead of the per invocation metric.
 * <p>
 * <b>Precondition.</b> {@code track} keeps the sample identity in the tracking set and returns early
 * for an already tracked sample, so the measured operation would degrade into the idempotent early
 * return after the first invocation. {@link PathState} therefore declares a per invocation reset that
 * restores the untracked precondition allocation free; a fixture rebuilt per invocation would be
 * attributed to the measured operation by {@code -prof gc}.
 * <p>
 * <b>Result consumption.</b> The build methods hand the assembled tracker to the blackhole and the
 * track methods hand the tracker to the blackhole after the measured call, so dead code elimination
 * cannot remove the measured operation.
 * <p>
 * <b>Logging.</b> Logging belongs to trial and iteration level assembly, never to the measured body
 * or the per invocation reset: both run once per invocation, so a log entry there would be part of
 * the measured region. The measured bodies, the per invocation reset and the per invocation state
 * accessors therefore carry no logging at all.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class FacadeBenchmark {

    /**
     * Measures one complete facade assembly: the builder, the SPI discovery it performs, the
     * selection and the tracker construction.
     *
     * @param blackhole consumer of the assembled tracker
     */
    @Benchmark
    public void facadeBuild(final Blackhole blackhole) {
        blackhole.consume(FacadePath.assemble());
    }

    /**
     * Measures one complete direct assembly: the SPI discovery, the same selection strategy as the
     * facade, {@code create()} and the tracker construction, without touching the facade.
     *
     * @param blackhole consumer of the assembled tracker
     */
    @Benchmark
    public void directBuild(final Blackhole blackhole) {
        blackhole.consume(DirectPath.assemble());
    }

    /**
     * Measures one {@code track} call on the tracker the facade path assembled for the iteration; the
     * precondition of the call is restored per invocation by the state.
     *
     * @param state     scan state holding the fixture of the current invocation
     * @param blackhole consumer of the tracker carrying the tracked sample
     */
    @Benchmark
    public void facadeTrack(final FacadePathState state, final Blackhole blackhole) {
        final ChangeTracker tracker = state.tracker();
        tracker.track(state.sample());
        blackhole.consume(tracker);
    }

    /**
     * Measures one {@code track} call on the tracker the direct path assembled for the iteration; the
     * precondition of the call is restored per invocation by the state.
     *
     * @param state     scan state holding the fixture of the current invocation
     * @param blackhole consumer of the tracker carrying the tracked sample
     */
    @Benchmark
    public void directTrack(final DirectPathState state, final Blackhole blackhole) {
        final ChangeTracker tracker = state.tracker();
        tracker.track(state.sample());
        blackhole.consume(tracker);
    }

    /**
     * The facade assembly path: the documented public api entry point a caller of the facade uses.
     */
    public static final class FacadePath {

        /**
         * Private constructor: this class is a static assembly entry point.
         */
        private FacadePath() {
        }

        /**
         * Assembles one tracker through the facade, including the SPI discovery the facade performs
         * on its own, and without applying any caller configuration: neither a business identifier
         * nor a custom value type is registered, so both sides of every pair compare on the
         * unconfigured capability.
         *
         * @return the tracker assembled through the api facade
         */
        public static ChangeTracker assemble() {
            return ChangeTrackerFactory.builder().withDefaults().build();
        }
    }

    /**
     * The direct assembly path: the hand written caller code that repeats what the facade does, using
     * only the public extension point of the core module and never a facade type.
     */
    public static final class DirectPath {

        /** Provider name the documented selection strategy prefers, the same name the facade prefers. */
        public static final String DEFAULT_PROVIDER_NAME = "default-reflection";

        /**
         * Private constructor: this class is a static assembly entry point.
         */
        private DirectPath() {
        }

        /**
         * Discovers every provider visible to the caller with {@code ServiceLoader}, collecting them
         * by name exactly as the facade does, so the selection below sees the same provider set.
         *
         * @return the discovered providers keyed by their name
         */
        public static Map<String, TrackingCapabilityProvider> discoverProviders() {
            final Map<String, TrackingCapabilityProvider> providers = new HashMap<>();
            for (final TrackingCapabilityProvider provider : ServiceLoader.load(TrackingCapabilityProvider.class)) {
                providers.put(provider.getName(), provider);
            }
            return providers;
        }

        /**
         * Assembles one tracker from an already discovered provider set, repeating the documented
         * facade selection strategy: {@value #DEFAULT_PROVIDER_NAME} when present, otherwise the
         * alphabetically first name. An empty set is rejected with the same failure semantics the
         * facade uses, and a provider creation failure is propagated to the caller.
         *
         * @param providers the discovered providers keyed by their name, not null
         * @return the tracker assembled on the selected provider
         * @throws NullPointerException  if providers is null
         * @throws IllegalStateException if no provider is available to assemble a tracker
         */
        public static ChangeTracker assemble(final Map<String, TrackingCapabilityProvider> providers) {
            Objects.requireNonNull(providers, "providers");
            if (providers.isEmpty()) {
                throw new IllegalStateException("No TrackingCapabilityProviders found. "
                        + "Ensure at least one is available via ServiceLoader.");
            }
            final String selectedName = providers.containsKey(DEFAULT_PROVIDER_NAME)
                    ? DEFAULT_PROVIDER_NAME
                    : Collections.min(providers.keySet());
            final TrackingCapability<?> capability = providers.get(selectedName).create();
            return new ChangeTracker(capability);
        }

        /**
         * Assembles one tracker through the direct path: the real SPI discovery followed by the
         * selection and the construction of {@link #assemble(Map)}, without any caller
         * configuration.
         *
         * @return the tracker assembled through the direct core path
         */
        public static ChangeTracker assemble() {
            return assemble(discoverProviders());
        }
    }

    /**
     * Shared state of one side of a pair: it builds the iteration fixture from the frozen sample
     * family through the assembly path of its subclass and restores the precondition per invocation.
     * <p>
     * Both path states share this class, so both sides of a pair measure the same sample shape, the
     * same fixture lifetime and the same reset; only {@link #assembleTracker()} differs, which is the
     * quantity the pair is meant to isolate. The fixture belongs to one iteration: rebuilding the
     * sample or the tracker per invocation would be attributed to the measured operation by
     * {@code -prof gc}. {@code @State(Scope.Thread)} is resolved through the state class hierarchy,
     * so the concrete path states carry no state annotation of their own.
     */
    @State(Scope.Thread)
    public abstract static class PathState {

        /** Logger of the fixture assembly; the assembly runs outside the measured region. */
        private static final Logger log = LoggerFactory.getLogger(PathState.class);

        /** Sample of the current iteration, created once per iteration. */
        private Object iterationSample;

        /** Tracker of the current iteration, assembled once per iteration through the path. */
        private ChangeTracker iterationTracker;

        /**
         * Builds the fixture of one iteration through the assembly path of the concrete state:
         * the sample of the frozen default shape, the tracker of this path and the baseline
         * registration that makes the sample tracked before the first measured invocation.
         */
        @Setup(Level.Iteration)
        public void setUpIteration() {
            iterationSample = SampleFamily.create(SampleShape.defaults());
            iterationTracker = assembleTracker();
            iterationTracker.track(iterationSample);
            log.info("Iteration fixture assembled on the {} path", getClass().getSimpleName());
        }

        /**
         * Returns the sample of the iteration to the untracked precondition, so the next measured
         * invocation performs a whole {@code track} instead of taking the idempotent early return.
         * <p>
         * The reset runs once per invocation and therefore carries no logging; it must stay
         * allocation free, which is why it stops tracking the existing sample instead of rebuilding
         * the fixture.
         */
        @Setup(Level.Invocation)
        public void resetPrecondition() {
            iterationTracker.stopTracking(iterationSample);
        }

        /**
         * Assembles the tracker of this state through the path its concrete class represents.
         *
         * @return the tracker assembled by the path of the concrete state
         */
        protected abstract ChangeTracker assembleTracker();

        /**
         * Returns the sample of the current iteration.
         *
         * @return the sample built by {@link SampleFamily#create(SampleShape)}
         */
        public Object sample() {
            return iterationSample;
        }

        /**
         * Returns the tracker of the current iteration.
         *
         * @return the tracker assembled by the path of the concrete state
         */
        public ChangeTracker tracker() {
            return iterationTracker;
        }
    }

    /**
     * State of the facade side of a pair: it assembles its tracker through the api facade.
     */
    public static class FacadePathState extends PathState {

        /** {@inheritDoc} */
        @Override
        protected ChangeTracker assembleTracker() {
            return FacadePath.assemble();
        }
    }

    /**
     * State of the direct side of a pair: it assembles its tracker through the direct core path.
     */
    public static class DirectPathState extends PathState {

        /** {@inheritDoc} */
        @Override
        protected ChangeTracker assembleTracker() {
            return DirectPath.assemble();
        }
    }
}
