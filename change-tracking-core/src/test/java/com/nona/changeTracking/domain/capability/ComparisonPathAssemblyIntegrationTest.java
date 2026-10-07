package com.nona.changeTracking.domain.capability;

import com.nona.changeTracking.domain.model.changeset.Change;
import com.nona.changeTracking.domain.model.changeset.ChangeSet;
import com.nona.changeTracking.domain.model.changeset.ValueChange;
import com.nona.changeTracking.domain.model.tracking.BaselineSnapshot;
import com.nona.changeTracking.domain.model.tracking.ChangeTracker;
import com.nona.changeTracking.internal.capability.DefaultTrackingCapabilityProvider;
import com.nona.changeTracking.spi.TrackingCapabilityProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 比较与路径优化的装配面集成测试：用真实 SPI 装配的默认 provider、真实 capability 与真实
 * {@link ChangeTracker} 链路，验证单元测试用替身覆盖不到的面。
 * <p>
 * 覆盖：ServiceLoader 发现默认 provider 后的完整比较链路路径、经公开扩展点注册业务标识后集合项路径
 * 使用业务标识文本、基线捕获与恢复互操作，以及注册 {@code toString} 计数的不变值类型后零变更遍历
 * 不格式化标识文本。
 */
@DisplayName("比较与路径优化装配面集成测试")
class ComparisonPathAssemblyIntegrationTest {

    @BeforeEach
    void setUp() {
        CountingValue.TO_STRING_CALLS.set(0);
    }

    @Nested
    @DisplayName("真实 SPI 装配与完整链路")
    class RealAssembly {

        @Test
        @DisplayName("ServiceLoader 应发现默认 provider 并驱动完整比较链路的标量变更路径")
        void serviceLoaderDiscoveredProvider_shouldDriveTheWholeChain() {
            final TrackingCapabilityProvider provider = defaultProvider();
            final ChangeTracker tracker = new ChangeTracker(provider.create());
            final Order order = new Order();
            order.items.add(new LineItem(1L, "SKU-1"));

            tracker.track(order);
            order.status = "PAID";

            final List<Change> leaves = tracker.calculateChanges().getLeafChanges();

            assertThat(leaves).hasSize(1);
            assertThat(leaves.get(0)).isInstanceOf(ValueChange.class);
            assertThat(leaves.get(0).path()).isEqualTo("status");
        }

        @Test
        @DisplayName("经公开扩展点注册业务标识后，集合项路径应使用业务标识文本")
        void registeredBusinessIdentifier_shouldAppearInCollectionPaths() {
            final TrackingCapabilityProvider provider = defaultProvider();
            provider.withIdentifier(LineItem.class, item -> item.id);
            final ChangeTracker tracker = new ChangeTracker(provider.create());
            final Order order = new Order();
            final LineItem item = new LineItem(7L, "SKU-7");
            order.items.add(item);

            tracker.track(order);
            item.sku = "SKU-7-changed";

            final List<Change> leaves = tracker.calculateChanges().getLeafChanges();

            assertThat(leaves).extracting(Change::path).containsExactly("items[7].sku");
            assertThat(leaves.get(0).collectionFieldName()).isEqualTo("items");
        }

        @Test
        @DisplayName("基线捕获与恢复后应继续检测同一路径的变更")
        void baselineRoundTrip_shouldKeepDetectingTheSamePath() {
            final TrackingCapabilityProvider provider = defaultProvider();
            final ChangeTracker tracker = new ChangeTracker(provider.create());
            final Order order = new Order();
            tracker.track(order);
            order.status = "PAID";
            final BaselineSnapshot baseline = tracker.captureBaseline();

            final ChangeTracker restored = ChangeTracker.fromBaseline(provider.create(), baseline);
            final List<Change> leaves = restored.calculateChanges().getLeafChanges();

            assertThat(leaves).extracting(Change::path).containsExactly("status");
        }
    }

    @Nested
    @DisplayName("零变更遍历不格式化标识")
    class ZeroChangeNoFormat {

        @Test
        @DisplayName("注册 toString 计数的不变值类型后，零变更遍历不应调用其 toString")
        void zeroChangeWithRegisteredValueType_shouldNotFormatIdentifierText() {
            final TrackingCapabilityProvider provider = defaultProvider();
            provider.withValueType(CountingValue.class);
            final ChangeTracker tracker = new ChangeTracker(provider.create());
            final ValuedOrder order = new ValuedOrder();
            order.codes.add(new CountingValue("A"));
            order.codes.add(new CountingValue("B"));

            tracker.track(order);
            CountingValue.TO_STRING_CALLS.set(0);

            final ChangeSet changeSet = tracker.calculateChanges();
            final int calls = CountingValue.TO_STRING_CALLS.get();

            assertThat(changeSet.isEmpty()).isTrue();
            assertThat(changeSet.getAllChanges()).isEmpty();
            assertThat(calls).isZero();
        }
    }

    /**
     * 集成测试的订单探针。
     */
    static class Order {

        /** 业务标识。 */
        Long id = 1L;

        /** 状态字段。 */
        String status = "CREATED";

        /** 集合项。 */
        List<LineItem> items = new ArrayList<>();
    }

    /**
     * 集成测试的集合项探针。
     */
    static class LineItem {

        /** 业务标识。 */
        Long id;

        /** 商品编码。 */
        String sku;

        /**
         * 创建集合项。
         *
         * @param id  业务标识。
         * @param sku 商品编码。
         */
        LineItem(final Long id, final String sku) {
            this.id = id;
            this.sku = sku;
        }
    }

    /**
     * 集成测试的不可变值类型探针：{@code equals}/{@code hashCode} 基于 code，
     * {@code toString} 计入调用次数。
     */
    static final class CountingValue {

        /** {@code toString} 调用计数。 */
        static final AtomicInteger TO_STRING_CALLS = new AtomicInteger();

        /** 值。 */
        private final String code;

        /**
         * 创建值。
         *
         * @param code 值。
         */
        CountingValue(final String code) {
            this.code = code;
        }

        /**
         * 按 code 比较。
         *
         * @param other 待比较对象。
         * @return code 相同返回 true。
         */
        @Override
        public boolean equals(final Object other) {
            return other instanceof CountingValue that && this.code.equals(that.code);
        }

        /**
         * code 哈希。
         *
         * @return code 哈希。
         */
        @Override
        public int hashCode() {
            return this.code.hashCode();
        }

        /**
         * 计数并返回文本。
         *
         * @return 文本表示。
         */
        @Override
        public String toString() {
            TO_STRING_CALLS.incrementAndGet();
            return "value[" + this.code + "]";
        }
    }

    /**
     * 集成测试的携带值类型集合的探针。
     */
    static class ValuedOrder {

        /** 值类型集合。 */
        List<CountingValue> codes = new ArrayList<>();
    }

    /**
     * 经 ServiceLoader 发现默认 provider。
     *
     * @return 默认 provider 实例。
     * @throws IllegalStateException 如果未发现默认 provider。
     */
    private static TrackingCapabilityProvider defaultProvider() {
        for (final TrackingCapabilityProvider provider : ServiceLoader.load(TrackingCapabilityProvider.class)) {
            if (DefaultTrackingCapabilityProvider.class.isInstance(provider)) {
                return provider;
            }
        }
        throw new IllegalStateException("Default provider not discovered through ServiceLoader");
    }
}
