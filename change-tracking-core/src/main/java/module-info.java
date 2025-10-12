/**
 * @author nona9961
 * @since 2025/10/12
 */module change.tracking.core {
    requires transitive lombok;
    exports com.nona.changeTracking.domain.model.changeset;
    exports com.nona.changeTracking.domain.model.unitofwork;
    exports com.nona.changeTracking.domain.detector;
    exports com.nona.changeTracking.spi;
    uses com.nona.changeTracking.spi.SnapshotComparatorProvider;
    provides com.nona.changeTracking.spi.SnapshotComparatorProvider
            with com.nona.changeTracking.internal.detector.MapSnapshotComparatorProvider;
    uses com.nona.changeTracking.spi.SnapshotStrategyProvider;
    provides com.nona.changeTracking.spi.SnapshotStrategyProvider
            with com.nona.changeTracking.internal.snapshot.ReflectionSnapshotStrategyProvider;
}