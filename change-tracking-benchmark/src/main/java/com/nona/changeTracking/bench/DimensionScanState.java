package com.nona.changeTracking.bench;

import com.nona.changeTracking.api.ChangeTrackerFactory;
import com.nona.changeTracking.bench.sample.SampleFamily;
import com.nona.changeTracking.bench.sample.SampleShape;
import com.nona.changeTracking.domain.model.tracking.ChangeTracker;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared state of a dimension scan: it derives the {@link SampleShape} of one scanned dimension
 * level, builds the iteration fixture from the frozen sample family and registers the tracking
 * baseline.
 * <p>
 * A benchmark task declares one concrete subclass per scanned dimension carrying exactly one
 * {@code @Param} level, while every other dimension stays at {@link SampleShape#defaults()}; a scan
 * therefore never expands into the cartesian product of all dimensions.
 * <p>
 * The fixture belongs to one iteration: a per-invocation fixture reset has to work with the sample
 * and the tracker built here, because rebuilding either of them per invocation would be attributed
 * to the measured operation by {@code -prof gc}. {@code @State(Scope.Thread)} is resolved through
 * the state class hierarchy, so concrete scan states carry no state annotation of their own.
 */
@State(Scope.Thread)
public abstract class DimensionScanState {

    /** Logger of the fixture assembly; the assembly runs outside the measured region. */
    private static final Logger log = LoggerFactory.getLogger(DimensionScanState.class);

    /** Sample of the current iteration, created by the frozen sample family. */
    private Object sample;

    /** Tracker of the current iteration, assembled through the api facade. */
    private ChangeTracker tracker;

    /**
     * Builds the fixture of one iteration: the sample of the scanned shape through
     * {@link SampleFamily#create(SampleShape)}, the tracker assembled through
     * {@link ChangeTrackerFactory#builder()}, and the baseline registration that makes the sample
     * tracked before the first measured invocation.
     */
    @Setup(Level.Iteration)
    public void setUpIteration() {
        final SampleShape scanShape = shape();
        final Object iterationSample = SampleFamily.create(scanShape);
        final ChangeTracker iterationTracker = ChangeTrackerFactory.builder().withDefaults().build();
        iterationTracker.track(iterationSample);
        this.sample = iterationSample;
        this.tracker = iterationTracker;
        log.info("Iteration fixture assembled for {}: {} tracked on shape {}", getClass().getSimpleName(),
                iterationSample.getClass().getSimpleName(), scanShape);
    }

    /**
     * Returns the shape of the scan: the scanned dimension carries the {@code @Param} level, every
     * other dimension carries the {@link SampleShape} default.
     *
     * @return the shape derived from the scanned dimension level
     * @throws IllegalArgumentException if the scanned level is outside the range the sample family
     *                                  supports, thrown by the {@link SampleShape} constructor
     */
    public abstract SampleShape shape();

    /**
     * Returns the sample of the current iteration.
     *
     * @return the sample built by {@link SampleFamily#create(SampleShape)}
     */
    public Object sample() {
        return sample;
    }

    /**
     * Returns the tracker of the current iteration.
     *
     * @return the tracker assembled through the api facade
     */
    public ChangeTracker tracker() {
        return tracker;
    }
}
