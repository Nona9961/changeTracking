package com.nona.changeTracking.internal.capability;

import com.nona.changeTracking.domain.capability.TrackingCapability;
import com.nona.changeTracking.domain.model.snapshot.ValueNodeSnapshot;
import com.nona.changeTracking.spi.TrackingCapabilityProvider;

public class DefaultTrackingCapabilityProvider implements TrackingCapabilityProvider {

    public static final String NAME = "default-reflection";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public TrackingCapability<ValueNodeSnapshot> create() {
        return new DefaultTrackingCapability();
    }
}
