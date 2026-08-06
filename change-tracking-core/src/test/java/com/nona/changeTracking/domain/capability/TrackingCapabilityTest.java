package com.nona.changeTracking.domain.capability;

import com.nona.changeTracking.domain.model.snapshot.Snapshot;
import com.nona.changeTracking.domain.model.snapshot.ValueNodeSnapshot;
import com.nona.changeTracking.internal.capability.DefaultTrackingCapability;
import com.nona.changeTracking.spi.SnapshotStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

@DisplayName("TrackingCapability 接口契约测试")
@ExtendWith(MockitoExtension.class)
class TrackingCapabilityTest {

    @Mock
    private TrackingCapability<ValueNodeSnapshot> capability;

    @Mock
    private SnapshotStrategy<ValueNodeSnapshot> snapshotStrategy;

    @Mock
    private ComparisonStrategy<ValueNodeSnapshot> comparisonStrategy;

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

    @Nested
    @DisplayName("泛型类型安全契约（A5）")
    class GenericTypeSafetyTests {

        @Test
        @DisplayName("自定义快照策略应能声明其具体快照类型（编译期契约）")
        void customSnapshotStrategy_shouldDeclareConcreteSnapshotType() {
            final SnapshotStrategy<CustomSnapshot> strategy = new CustomSnapshotStrategy();

            final CustomSnapshot snapshot = strategy.createSnapshot("payload");

            assertNotNull(snapshot);
            assertEquals("payload", snapshot.getSnapshotData());
        }

        @Test
        @DisplayName("默认能力应暴露绑定具体快照类型的快照策略，而非 raw 类型")
        void defaultCapability_shouldExposeTypedSnapshotStrategy() {
            final TrackingCapability<ValueNodeSnapshot> typedCapability =
                    new DefaultTrackingCapability(TrackingConfiguration.empty());

            final SnapshotStrategy<ValueNodeSnapshot> strategy = typedCapability.getSnapshotStrategy();
            final ValueNodeSnapshot snapshot = strategy.createSnapshot(new Object());

            assertNotNull(snapshot);
            assertInstanceOf(ValueNodeSnapshot.class, snapshot);
        }
    }

    /**
     * 测试用的自定义快照类型：验证 SnapshotStrategy 泛型契约。
     *
     * @param data 快照载荷。
     */
    private record CustomSnapshot(String data) implements Snapshot<String> {

        /**
         * {@inheritDoc}
         */
        @Override
        public String getSnapshotData() {
            return data;
        }
    }

    /**
     * 测试用的自定义快照策略：声明其产出的具体快照类型，而非 raw 类型。
     */
    private static final class CustomSnapshotStrategy implements SnapshotStrategy<CustomSnapshot> {

        /**
         * {@inheritDoc}
         */
        @Override
        public CustomSnapshot createSnapshot(final Object entity) {
            return new CustomSnapshot(String.valueOf(entity));
        }
    }
}
