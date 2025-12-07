package com.nona.changeTracking.internal.capability;

import com.nona.changeTracking.domain.capability.ComparisonStrategy;
import com.nona.changeTracking.domain.capability.TrackingCapability;
import com.nona.changeTracking.domain.capability.ValueNodeComparisonStrategy;
import com.nona.changeTracking.domain.model.snapshot.ValueNodeSnapshot;
import com.nona.changeTracking.internal.snapshot.ValueNodeSnapshotStrategy;
import com.nona.changeTracking.spi.SnapshotStrategy;

/**
 * 基于反射的默认追踪能力实现。
 * <p>
 * 使用 {@link ValueNodeSnapshotStrategy} 创建快照，
 * 使用 {@link ValueNodeComparisonStrategy} 比较变更。
 * <p>
 * 这是框架的默认实现，通过 SPI 机制由 {@link DefaultTrackingCapabilityProvider} 提供。
 */
public class DefaultTrackingCapability implements TrackingCapability<ValueNodeSnapshot> {

    private final ValueNodeSnapshotStrategy snapshotStrategy = new ValueNodeSnapshotStrategy();
    private final ValueNodeComparisonStrategy comparisonStrategy = new ValueNodeComparisonStrategy();

    /**
     * {@inheritDoc}
     */
    @Override
    public SnapshotStrategy getSnapshotStrategy() {
        return snapshotStrategy;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ComparisonStrategy<ValueNodeSnapshot> getComparisonStrategy() {
        return comparisonStrategy;
    }
}
