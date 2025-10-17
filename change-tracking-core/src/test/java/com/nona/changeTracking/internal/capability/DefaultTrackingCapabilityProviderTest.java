package com.nona.changeTracking.internal.capability;

import com.nona.changeTracking.domain.capability.ComparisonStrategy;
import com.nona.changeTracking.domain.capability.TrackingCapability;
import com.nona.changeTracking.domain.capability.ValueNodeComparisonStrategy;
import com.nona.changeTracking.internal.snapshot.ValueNodeSnapshotStrategy;
import com.nona.changeTracking.spi.SnapshotStrategy;
import com.nona.changeTracking.spi.TrackingCapabilityProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DefaultTrackingCapabilityProvider 测试")
class DefaultTrackingCapabilityProviderTest {

    private TrackingCapabilityProvider provider;

    @BeforeEach
    void setUp() {
        provider = new DefaultTrackingCapabilityProvider();
    }

    @Test
    @DisplayName("getName() 应返回一个固定的、唯一的名称")
    void getName_shouldReturnFixedUniqueName() {
        assertEquals("default-reflection", provider.getName());
    }

    @Test
    @DisplayName("create() 应创建一个包含 ValueNode 策略的 TrackingCapability")
    void create_shouldCreateCapabilityWithDefaultStrategies() {
        final TrackingCapability<?> capability = provider.create();

        assertNotNull(capability);

        // 验证快照策略
        final SnapshotStrategy snapshotStrategy = capability.getSnapshotStrategy();
        assertInstanceOf(ValueNodeSnapshotStrategy.class, snapshotStrategy);

        // 验证比较策略
        final ComparisonStrategy<?> comparisonStrategy = capability.getComparisonStrategy();
        assertInstanceOf(ValueNodeComparisonStrategy.class, comparisonStrategy);
    }
}
