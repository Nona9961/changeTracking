/**
 * @author nona9961
 * @since 2025/10/12
 */module change.tracking.core {
    requires static transitive lombok;
    exports com.nona.changeTracking.domain.model.changeset;
    exports com.nona.changeTracking.domain.model.snapshot;
    exports com.nona.changeTracking.domain.model.unitofwork;
    exports com.nona.changeTracking.domain.capability;
    exports com.nona.changeTracking.spi;
    exports com.nona.changeTracking.internal.util;
    uses com.nona.changeTracking.spi.TrackingCapabilityProvider;
    provides com.nona.changeTracking.spi.TrackingCapabilityProvider
            with com.nona.changeTracking.internal.capability.DefaultTrackingCapabilityProvider;
}