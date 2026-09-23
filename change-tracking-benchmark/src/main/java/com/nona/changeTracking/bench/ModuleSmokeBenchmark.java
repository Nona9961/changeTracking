package com.nona.changeTracking.bench;

import com.nona.changeTracking.api.ChangeTrackerFactory;
import com.nona.changeTracking.bench.env.EnvironmentRecordCollector;
import com.nona.changeTracking.bench.env.EnvironmentRecordWriter;
import com.nona.changeTracking.bench.sample.SampleFamily;
import com.nona.changeTracking.bench.sample.SampleMutator;
import com.nona.changeTracking.bench.sample.SampleShape;
import com.nona.changeTracking.domain.model.tracking.ChangeTracker;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Module smoke benchmark: proves that the benchmark module builds, shades, runs and
 * produces a JSON result on a minimal sample.
 * <p>
 * It is an assembly check, not a performance statement: the measurement targets of this
 * repository are defined by the snapshot, diff, facade and end to end benchmark tasks,
 * which reuse the sample family and the run command frozen here.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class ModuleSmokeBenchmark {

    /** Directory receiving the JMH result file and the environment record. */
    private static final Path RESULT_DIRECTORY = Path.of("target", "benchmark-results");

    /** Run logger of the benchmark module; it records assembly and record writing, never the measured body. */
    private static final Logger log = LoggerFactory.getLogger(ModuleSmokeBenchmark.class);

    /** Tracker assembled through the api facade from the providers discovered on the shaded classpath. */
    private ChangeTracker tracker;

    /** Minimal sample root created by the frozen sample family. */
    private Object sample;

    /**
     * Builds the tracker through the api facade, creates the default sample and archives the
     * run time environment record into the benchmark result directory.
     */
    @Setup(Level.Trial)
    public void setUp() {
        this.tracker = ChangeTrackerFactory.builder().withDefaults().build();
        this.sample = SampleFamily.create(SampleShape.defaults());
        final Path record = EnvironmentRecordWriter.write(EnvironmentRecordCollector.capture(), RESULT_DIRECTORY);
        log.info("Benchmark module assembled, environment record archived to {}", record);
    }

    /**
     * Tracks the minimal sample, applies one property change and computes the change set.
     *
     * @return the number of detected changes
     */
    @Benchmark
    public int trackAndDiffMinimalSample() {
        tracker.track(sample);
        SampleMutator.changeField(sample, "status");
        return tracker.calculateChanges().getLeafChanges().size();
    }
}