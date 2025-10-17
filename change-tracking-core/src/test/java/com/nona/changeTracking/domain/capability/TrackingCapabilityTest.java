package com.nona.changeTracking.domain.capability;

import com.nona.changeTracking.spi.SnapshotStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

@DisplayName("TrackingCapability 接口契约测试")
@ExtendWith(MockitoExtension.class)
class TrackingCapabilityTest {

    @Mock
    private TrackingCapability capability;

    @Mock
    private SnapshotStrategy snapshotStrategy;

    @Mock
    private ComparisonStrategy<?> comparisonStrategy;

    @Test
    @DisplayName("应能提供一个 SnapshotStrategy")
    void shouldProvideSnapshotStrategy() {
        when(capability.getSnapshotStrategy()).thenReturn(snapshotStrategy);
        assertNotNull(capability.getSnapshotStrategy());
    }

    @Test
    @DisplayName("应能提供一个 ComparisonStrategy")
    void shouldProvideComparisonStrategy() {
        doReturn(comparisonStrategy).when(capability).getComparisonStrategy();
        assertNotNull(capability.getComparisonStrategy());
    }
}
