package com.nona.changeTracking.internal.capability;

import com.nona.changeTracking.domain.capability.ComparisonStrategy;
import com.nona.changeTracking.domain.capability.TrackingCapability;
import com.nona.changeTracking.domain.capability.ValueNodeComparisonStrategy;
import com.nona.changeTracking.domain.model.snapshot.ValueNodeSnapshot;
import com.nona.changeTracking.internal.snapshot.ValueNodeSnapshotStrategy;
import com.nona.changeTracking.spi.SnapshotStrategy;

public class DefaultTrackingCapability implements TrackingCapability<ValueNodeSnapshot> {

    private final ValueNodeSnapshotStrategy snapshotStrategy = new ValueNodeSnapshotStrategy();
    private final ValueNodeComparisonStrategy comparisonStrategy = new ValueNodeComparisonStrategy();

    @Override
    public SnapshotStrategy getSnapshotStrategy() {
        return snapshotStrategy;
    }

    @Override
    public ComparisonStrategy<ValueNodeSnapshot> getComparisonStrategy() {
        return comparisonStrategy;
    }
}
