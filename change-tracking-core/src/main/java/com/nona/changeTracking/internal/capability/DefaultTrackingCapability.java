package com.nona.changeTracking.internal.capability;

import com.nona.changeTracking.domain.capability.ComparisonStrategy;
import com.nona.changeTracking.domain.capability.TrackingCapability;
import com.nona.changeTracking.domain.capability.TrackingConfiguration;
import com.nona.changeTracking.domain.capability.ValueNodeComparisonStrategy;
import com.nona.changeTracking.domain.model.snapshot.ValueNodeSnapshot;
import com.nona.changeTracking.internal.snapshot.ValueNodeSnapshotStrategy;
import com.nona.changeTracking.spi.SnapshotStrategy;

import java.util.Objects;

/**
 * 基于反射的默认追踪能力实现。
 * <p>
 * 使用 {@link ValueNodeSnapshotStrategy} 创建快照，
 * 使用 {@link ValueNodeComparisonStrategy} 比较变更。
 * <p>
 * 这是框架的默认实现，通过 SPI 机制由 {@link DefaultTrackingCapabilityProvider} 提供。
 * 支持通过 {@link TrackingConfiguration} 配置自定义值类型和标识符提取器。
 */
public class DefaultTrackingCapability implements TrackingCapability<ValueNodeSnapshot> {

    private final ValueNodeSnapshotStrategy snapshotStrategy;
    private final ValueNodeComparisonStrategy comparisonStrategy;

    /**
     * 使用指定配置创建追踪能力实例。
     *
     * @param configuration 追踪配置，不能为 null。
     * @throws NullPointerException 如果 configuration 为 null。
     */
    public DefaultTrackingCapability(final TrackingConfiguration configuration) {
        Objects.requireNonNull(configuration, "Configuration cannot be null.");
        this.snapshotStrategy = new ValueNodeSnapshotStrategy(configuration);
        this.comparisonStrategy = new ValueNodeComparisonStrategy();
    }

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
