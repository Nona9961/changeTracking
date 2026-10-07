package com.nona.changeTracking.bench;

import com.nona.changeTracking.bench.env.EnvironmentRecordCollector;
import com.nona.changeTracking.bench.env.EnvironmentRecordWriter;
import com.nona.changeTracking.bench.result.ColdSampleTable;
import com.nona.changeTracking.bench.sample.SampleFamily;
import com.nona.changeTracking.bench.sample.SampleShape;
import com.nona.changeTracking.domain.capability.TrackingCapability;
import com.nona.changeTracking.domain.model.changeset.ChangeSet;
import com.nona.changeTracking.domain.model.snapshot.ValueNodeSnapshot;
import com.nona.changeTracking.domain.model.tracking.BaselineSnapshot;
import com.nona.changeTracking.domain.model.tracking.ChangeTracker;
import com.nona.changeTracking.spi.SnapshotStrategy;
import com.sun.management.ThreadMXBean;
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
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Cold first use of the type processing caches: the first snapshot through the target strategy and
 * the first complete calculation after a restored baseline, each measured with the single shot
 * protocol frozen for that state.
 * <p>
 * <b>Protocol</b> (first use of a type): independent forks, single thread, zero warmup,
 * one measurement iteration with batch size one, several forks sampled. The class therefore carries
 * {@code @BenchmarkMode(SingleShotTime)}, {@code @Warmup(iterations = 0)},
 * {@code @Measurement(iterations = 1, batchSize = 1)}, {@code @Fork(}{@value #FORKS}{@code )} and
 * {@code @Threads(1)}; the two measured methods are the two documented cold metrics, one for the
 * first target strategy snapshot and one for the first full calculation after the baseline was
 * restored. Neither metric includes process start up, and neither is reported as steady state.
 * <p>
 * <b>Preparation must stay off the measured path.</b> The trial assembly builds the sample through
 * the frozen sample family and the baseline through {@link ColdBaselineFixture}; it must not call the
 * snapshot strategy, {@code track} or either cache, otherwise the measured operation would no longer
 * be a first use. The assembly does warm the shared node types and the sample family, so the recorded
 * state is the target cache being cold rather than a cold JVM.
 * <p>
 * <b>Raw sampling table.</b> The single shot protocol is reported as raw samples, not through the
 * steady state comparator: each measured invocation surrounds the target operation with a
 * {@code System.nanoTime} pair and reads the thread allocated bytes before and after it, and appends
 * one row per invocation (that is, one row per fork) to {@link com.nona.changeTracking.bench.result.ColdSampleTable}.
 * Time and target allocation are therefore recorded by the carrier itself, the metering facility cost
 * is measured once per trial and stored on every row, and the rows are never fed into
 * {@code BenchmarkResultComparator}, which requires both metric kinds in the JMH result shape.
 * <p>
 * <b>Result consumption.</b> Both measured methods consume their result through the {@link Blackhole},
 * so dead code elimination cannot remove the measured operation, and the metering reads never replace
 * the measured call.
 * <p>
 * <b>Logging.</b> Logging is confined to the trial assembly: the measured bodies run once per
 * invocation and a log entry there would be part of the measured window.
 */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 0)
@Measurement(iterations = 1, batchSize = 1)
@Fork(FirstUseCacheStateBenchmark.FORKS)
@Threads(1)
@State(Scope.Thread)
public class FirstUseCacheStateBenchmark {

    /** Number of independent forks sampled by the cold first use protocol. */
    public static final int FORKS = 5;

    /** Protocol tag recorded on every raw sample row of this benchmark. */
    public static final String PROTOCOL = "first-type-use";

    /** Directory receiving the raw sampling table and the environment record. */
    public static final Path RESULT_DIRECTORY = Path.of("target", "benchmark-results");

    /** Logger of the trial assembly; the assembly runs outside the measured region. */
    private static final Logger log = LoggerFactory.getLogger(FirstUseCacheStateBenchmark.class);

    /**
     * Thread allocation meter of the metering facility: the thread allocated bytes around the target
     * operation are read through it, and the same reads measure the facility cost once per trial.
     */
    private static final ThreadMXBean THREAD_MX_BEAN =
            (ThreadMXBean) ManagementFactory.getThreadMXBean();

    /** Sample of this fork, created by the frozen sample family at trial level. */
    private Object sample;

    /** Hand built baseline of the sample, built without calling the target strategy. */
    private BaselineSnapshot baseline;

    /** Capability of this fork; its caches are still cold when the measured methods run. */
    private TrackingCapability<ValueNodeSnapshot> capability;

    /** Snapshot strategy of the capability, the target strategy of the cold snapshot metric. */
    private SnapshotStrategy<ValueNodeSnapshot> strategy;

    /** Cost of the metering facility itself, measured once per trial and recorded on every row. */
    private double meteringOverheadNanos;

    /**
     * Assembles the trial fixture: the sample of the default shape through the frozen sample family,
     * the hand built baseline through {@link ColdBaselineFixture}, the capability through the public
     * extension point, the metering overhead of one empty metering sequence, and the environment
     * record of this run.
     * <p>
     * The assembly must not call the snapshot strategy or either cache, so both the class level and
     * the configuration level cache are cold when the measured method runs for the first time.
     */
    @Setup(Level.Trial)
    public void setUpTrial() {
        this.sample = SampleFamily.create(SampleShape.defaults());
        this.baseline = ColdBaselineFixture.baselineOf(this.sample);
        this.capability = defaultCapability();
        this.strategy = this.capability.getSnapshotStrategy();
        this.meteringOverheadNanos = measureMeteringOverhead();
        final Path record = EnvironmentRecordWriter.write(EnvironmentRecordCollector.capture(), RESULT_DIRECTORY);
        log.info("Cold first use fixture assembled without touching the measured strategy or caches; "
                + "environment record archived to {}", record);
    }

    /**
     * Measures the first target strategy snapshot of the fork and appends its raw sample row.
     * <p>
     * The measured region is the single {@code createSnapshot} call between the metering reads; the
     * row is appended afterwards, outside the measured window.
     *
     * @param blackhole consumer of the produced snapshot
     */
    @Benchmark
    public void firstTargetStrategySnapshot(final Blackhole blackhole) {
        final long allocatedBefore = allocatedBytesOfCurrentThread();
        final long start = System.nanoTime();
        final ValueNodeSnapshot snapshot = this.strategy.createSnapshot(this.sample);
        final long elapsedNanos = System.nanoTime() - start;
        final long allocatedBytes = allocatedBytesOfCurrentThread() - allocatedBefore;
        blackhole.consume(snapshot);
        appendRawSample("firstTargetStrategySnapshot", elapsedNanos, allocatedBytes);
    }

    /**
     * Measures the first complete calculation of the fork after the baseline was restored and appends
     * its raw sample row.
     * <p>
     * The measured region covers the tracker creation on the restored baseline plus the calculation
     * over the cold caches; the sample was not dehydrated before, so the current state snapshot inside
     * the calculation is the first target strategy use of this fork.
     *
     * @param blackhole consumer of the computed change set
     */
    @Benchmark
    public void firstFullCalculationAfterBaselineRestore(final Blackhole blackhole) {
        final long allocatedBefore = allocatedBytesOfCurrentThread();
        final long start = System.nanoTime();
        final ChangeTracker tracker = ChangeTracker.fromBaseline(this.capability, this.baseline);
        final ChangeSet changeSet = tracker.calculateChanges();
        final long elapsedNanos = System.nanoTime() - start;
        final long allocatedBytes = allocatedBytesOfCurrentThread() - allocatedBefore;
        blackhole.consume(changeSet);
        appendRawSample("firstFullCalculationAfterBaselineRestore", elapsedNanos, allocatedBytes);
    }

    /**
     * Appends one raw sample row of this fork for the given measured method; the row is appended after
     * the measured window closed.
     *
     * @param measuredMethod    simple name of the measured method
     * @param elapsedNanos      time of the target operation in nanoseconds
     * @param allocatedBytes    thread allocated bytes around the target operation
     */
    private void appendRawSample(final String measuredMethod, final long elapsedNanos, final long allocatedBytes) {
        ColdSampleTable.append(RESULT_DIRECTORY, new ColdSampleTable.ColdSample(
                FirstUseCacheStateBenchmark.class.getName() + "." + measuredMethod,
                Map.of(),
                PROTOCOL,
                (double) elapsedNanos,
                allocatedBytes,
                this.meteringOverheadNanos));
    }

    /**
     * Discovers the default capability through the public extension point: the documented provider
     * selection of the facade, implemented once by {@link CacheStateBenchmark#capabilityProvider()}.
     * The capability is created, but no snapshot is taken, so the measured caches stay cold.
     *
     * @return the default capability of this fork, its caches still cold
     */
    @SuppressWarnings("unchecked")
    private static TrackingCapability<ValueNodeSnapshot> defaultCapability() {
        return (TrackingCapability<ValueNodeSnapshot>) CacheStateBenchmark.capabilityProvider().create();
    }

    /**
     * Measures the cost of one empty metering sequence: the two {@code nanoTime} reads and the two
     * thread allocation reads, without any target operation between them. The cost is stored on every
     * row, so the facility cost is reported next to the target allocation instead of inside it.
     *
     * @return the cost of one empty metering sequence in nanoseconds
     */
    private static double measureMeteringOverhead() {
        final long start = System.nanoTime();
        allocatedBytesOfCurrentThread();
        allocatedBytesOfCurrentThread();
        return (double) (System.nanoTime() - start);
    }

    /**
     * Reads the allocated bytes of the current thread.
     *
     * @return the allocated bytes of the current thread
     */
    private static long allocatedBytesOfCurrentThread() {
        return THREAD_MX_BEAN.getThreadAllocatedBytes(Thread.currentThread().getId());
    }

    /**
     * Returns the sample of this fork.
     *
     * @return the sample built by the frozen sample family
     */
    public Object sample() {
        return this.sample;
    }

    /**
     * Returns the hand built baseline of this fork.
     *
     * @return the baseline built by {@link ColdBaselineFixture}
     */
    public BaselineSnapshot baseline() {
        return this.baseline;
    }

    /**
     * Returns the capability of this fork.
     *
     * @return the capability whose caches are cold before the first measured operation
     */
    public TrackingCapability<ValueNodeSnapshot> capability() {
        return this.capability;
    }

    /**
     * Returns the metering overhead measured by the trial assembly.
     *
     * @return cost of one empty metering sequence in nanoseconds
     */
    public double meteringOverheadNanos() {
        return this.meteringOverheadNanos;
    }
}