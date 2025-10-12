/**
 * @author nona9961
 * @since 2025/10/12
 */module change.tracking.api {
    exports com.nona.changeTracking.api;

    requires transitive change.tracking.core;
    uses com.nona.changeTracking.spi.SnapshotStrategyProvider;
    opens com.nona.changeTracking.api to change.tracking.core;
}