package com.nona.changeTracking.tracking;

import com.nona.changeTracking.change.Change;
import com.nona.changeTracking.change.ChangeSet;
import com.nona.changeTracking.change.ItemAddedChange;
import com.nona.changeTracking.snapshot.ValueNodeSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 追踪能力 SPI 装配面集成测试：验证插件发现、能力名称与快照类型契约，以及显式 provider 与
 * ServiceLoader 发现结果在统一变更模型下都能装配出可用的检测链路。
 * <p>
 * 覆盖单元测试用替身覆盖不到的装配面：{@code META-INF/services} 的真实注册、provider 名称与
 * 快照类型的公开契约、注册业务标识后的集合项定位，以及默认 provider 的两种装配方式。
 */
@DisplayName("追踪能力 SPI 装配面集成测试")
class TrackingCapabilityAssemblyIntegrationTest {

    @Nested
    @DisplayName("ServiceLoader 发现")
    class ServiceLoaderDiscovery {

        @Test
        @DisplayName("ServiceLoader 能发现默认 provider，且其能力名称与声明一致")
        void serviceLoader_shouldDiscoverTheDefaultProvider() {
            final TrackingCapabilityProvider provider = discoveredDefaultProvider();

            assertThat(provider.getName()).isEqualTo(DefaultTrackingCapabilityProvider.NAME);
        }

        @Test
        @DisplayName("ServiceLoader 发现的能力可装配出可用的检测链路并检出字段变化")
        void serviceLoaderDiscoveredCapability_shouldDetectAFieldChange() {
            final ChangeTracker tracker = new ChangeTracker(discoveredDefaultProvider().create());
            final Order order = new Order();
            tracker.track(order);
            order.status = "PAID";

            final ChangeSet changeSet = tracker.calculateChanges();

            assertThat(changeSet.getLeafChanges()).hasSize(1);
            assertThat(changeSet.getLeafChanges().get(0).fullPath()).isEqualTo("status");
            assertThat(changeSet.getAllChanges()).extracting(Change::fullPath).containsExactly("status");
        }
    }

    @Nested
    @DisplayName("显式 provider 装配")
    class ExplicitProviderAssembly {

        @Test
        @DisplayName("显式 provider 使用与 ServiceLoader 发现一致的名称并装配出可用检测链路")
        void explicitProvider_shouldExposeTheSameNameAndDetectChanges() {
            final TrackingCapabilityProvider provider = new DefaultTrackingCapabilityProvider();
            provider.withIdentifier(LineItem.class, item -> item.id);

            assertThat(provider.getName()).isEqualTo(DefaultTrackingCapabilityProvider.NAME);

            final ChangeTracker tracker = new ChangeTracker(provider.create());
            final Order order = new Order();
            tracker.track(order);
            order.items.add(new LineItem(100L, "SKU-100"));

            final ChangeSet changeSet = tracker.calculateChanges();

            assertThat(changeSet.getLeafChanges()).hasSize(1);
            assertThat(changeSet.getLeafChanges().get(0)).isInstanceOf(ItemAddedChange.class);
            assertThat(changeSet.getLeafChanges().get(0).fullPath()).isEqualTo("items[100]");
        }

        @Test
        @DisplayName("注册业务标识后经发现与显式两种装配都产出业务标识定位")
        void registeredIdentifier_shouldDriveCollectionLocations() {
            final ChangeTracker discoveredTracker = trackerWithRegisteredIdentifier(discoveredDefaultProvider());
            final ChangeTracker explicitTracker = trackerWithRegisteredIdentifier(new DefaultTrackingCapabilityProvider());

            trackOrderWithChangedItem(discoveredTracker);
            trackOrderWithChangedItem(explicitTracker);

            assertThat(discoveredTracker.calculateChanges().getLeafChanges())
                    .extracting(Change::fullPath).containsExactly("items[7].sku");
            assertThat(explicitTracker.calculateChanges().getLeafChanges())
                    .extracting(Change::fullPath).containsExactly("items[7].sku");
        }
    }

    @Nested
    @DisplayName("能力与快照类型契约")
    class CapabilityContract {

        @Test
        @DisplayName("默认能力的快照策略产出 ValueNodeSnapshot，且比较策略声明同一类型")
        void defaultCapability_shouldDeclareTheValueNodeSnapshotContract() {
            final TrackingCapability<ValueNodeSnapshot> capability =
                    new DefaultTrackingCapabilityProvider().create();

            final ValueNodeSnapshot snapshot = capability.getSnapshotStrategy().createSnapshot(new Order());

            assertThat(capability.getComparisonStrategy().getSupportedSnapshotType()).isEqualTo(ValueNodeSnapshot.class);
            assertThat(snapshot).isInstanceOf(capability.getComparisonStrategy().getSupportedSnapshotType());
        }
    }

    /**
     * 经 ServiceLoader 发现默认 provider。
     *
     * @return 默认 provider 实例
     * @throws IllegalStateException 如果未发现默认 provider
     */
    private static TrackingCapabilityProvider discoveredDefaultProvider() {
        for (final TrackingCapabilityProvider candidate : ServiceLoader.load(TrackingCapabilityProvider.class)) {
            if (DefaultTrackingCapabilityProvider.class.isInstance(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Default provider not discovered through ServiceLoader");
    }

    /**
     * 在给定 provider 上注册业务标识并装配追踪器。
     *
     * @param provider 待注册的 provider
     * @return 使用集合项业务标识的追踪器
     */
    private static ChangeTracker trackerWithRegisteredIdentifier(final TrackingCapabilityProvider provider) {
        provider.withIdentifier(LineItem.class, item -> item.id);
        return new ChangeTracker(provider.create());
    }

    /**
     * 追踪一个已含单项的订单并修改该项字段。
     *
     * @param tracker 待使用的追踪器
     */
    private static void trackOrderWithChangedItem(final ChangeTracker tracker) {
        final Order order = new Order();
        order.items.add(new LineItem(7L, "SKU-7"));
        tracker.track(order);
        order.items.get(0).sku = "SKU-7-changed";
    }

    /**
     * 集成测试的订单探针。
     */
    static class Order {

        /**
         * 业务标识。
         */
        Long id = 1L;

        /**
         * 状态字段。
         */
        String status = "CREATED";

        /**
         * 集合项。
         */
        List<LineItem> items = new ArrayList<>();
    }

    /**
     * 集成测试的集合项探针。
     */
    static class LineItem {

        /**
         * 业务标识。
         */
        Long id;

        /**
         * 商品编码。
         */
        String sku;

        /**
         * 创建集合项。
         *
         * @param id  业务标识
         * @param sku 商品编码
         */
        LineItem(final Long id, final String sku) {
            this.id = id;
            this.sku = sku;
        }
    }
}
