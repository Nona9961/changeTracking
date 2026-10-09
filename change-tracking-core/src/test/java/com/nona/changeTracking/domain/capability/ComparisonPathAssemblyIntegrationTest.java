package com.nona.changeTracking.domain.capability;

import com.nona.changeTracking.domain.model.changeset.Change;
import com.nona.changeTracking.domain.model.changeset.ChangeLocation;
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
 * 使用业务标识文本、深链多层输出的逐层定位取值与标识文本的按需准备、同一集合项下多个字段变化
 * 复用同一祖先定位、重复读取路径不重建文本、基线捕获与恢复互操作，以及注册 {@code toString}
 * 计数的不变值类型后零变更遍历不格式化标识文本。
 */
@DisplayName("比较与路径优化装配面集成测试")
class ComparisonPathAssemblyIntegrationTest {

    @BeforeEach
    void setUp() {
        CountingValue.TO_STRING_CALLS.set(0);
        CountingId.TO_STRING_CALLS.set(0);
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
    @DisplayName("深链定位按需构造与前缀共享")
    class DeepChainLocationSharing {

        @Test
        @DisplayName("最深叶子变更的定位与独立构造的前缀链取值一致，两视图元数据一致")
        void deepestLeafChange_shouldMatchAnIndependentlyBuiltPrefixChain() {
            final TrackingCapabilityProvider provider = deepChainProvider();
            final ChangeTracker tracker = new ChangeTracker(provider.create());
            final Warehouse warehouse = warehouse();
            tracker.track(warehouse);
            warehouse.shelves.get(0).crates.get(0).weight = 2;

            final ChangeSet changeSet = tracker.calculateChanges();
            final Change leaf = changeSet.getLeafChanges().get(0);
            final Change sameLeaf = changeSet.getAllChanges().stream()
                    .filter(change -> "shelves[1].crates[id[C1]].weight".equals(change.fullPath()))
                    .findFirst()
                    .orElseThrow();

            final ChangeLocation independentlyBuilt = ChangeLocation.field(
                    ChangeLocation.collectionItem(
                            ChangeLocation.field(
                                    ChangeLocation.collectionItem(
                                            ChangeLocation.field(ChangeLocation.root(), "shelves"),
                                            "1"),
                                    "crates"),
                            "id[C1]"),
                    "weight");

            assertThat(leaf.location()).isEqualTo(independentlyBuilt);
            assertThat(leaf.path()).isEqualTo("shelves[1].crates[id[C1]].weight");
            assertThat(leaf.fullPath()).isEqualTo("shelves[1].crates[id[C1]].weight");
            assertThat(leaf.relativePath()).isEqualTo("weight");
            assertThat(leaf.fieldName()).isEqualTo("weight");
            assertThat(leaf.collectionFieldName()).isEqualTo("crates");
            assertThat(leaf.isParentCollection()).isFalse();
            assertThat(sameLeaf.location()).isEqualTo(leaf.location());
            assertThat(sameLeaf.relativePath()).isEqualTo(leaf.relativePath());
            assertThat(sameLeaf.collectionFieldName()).isEqualTo(leaf.collectionFieldName());
        }

        @Test
        @DisplayName("每层都有变更时逐层输出定位取值正确，且活动标识只格式化一次")
        void changesAtEveryLevel_shouldReportEveryLevelLocationAndFormatTheIdentityOnce() {
            final TrackingCapabilityProvider provider = deepChainProvider();
            final ChangeTracker tracker = new ChangeTracker(provider.create());
            final Warehouse warehouse = warehouse();
            tracker.track(warehouse);
            warehouse.status = "CLOSED";
            final Shelf shelf = warehouse.shelves.get(0);
            shelf.code = "s2";
            final Crate crate = shelf.crates.get(0);
            crate.weight = 2;
            crate.label = "c2";

            CountingId.TO_STRING_CALLS.set(0);
            final ChangeSet changeSet = tracker.calculateChanges();

            assertThat(changeSet.getAllChanges()).extracting(Change::fullPath).containsExactly(
                    "status",
                    "shelves",
                    "shelves[1]",
                    "shelves[1].code",
                    "shelves[1].crates",
                    "shelves[1].crates[id[C1]]",
                    "shelves[1].crates[id[C1]].weight",
                    "shelves[1].crates[id[C1]].label");
            assertThat(CountingId.TO_STRING_CALLS).hasValue(1);

            final Change shelfContainer = changeWithPath(changeSet, "shelves[1]");
            final Change crateContainer = changeWithPath(changeSet, "shelves[1].crates[id[C1]]");
            final Change weightLeaf = changeWithPath(changeSet, "shelves[1].crates[id[C1]].weight");

            assertThat(shelfContainer.collectionFieldName()).isEqualTo("shelves");
            assertThat(shelfContainer.isParentCollection()).isTrue();
            assertThat(shelfContainer.fieldName()).isNull();
            assertThat(crateContainer.collectionFieldName()).isEqualTo("crates");
            assertThat(crateContainer.isParentCollection()).isTrue();
            assertThat(ChangeLocation.field(crateContainer.location(), "weight"))
                    .isEqualTo(weightLeaf.location());
        }

        @Test
        @DisplayName("同一集合项下多个字段变更复用同一祖先定位，标识文本只格式化一次")
        void multipleFieldChangesUnderTheSameItem_shouldReuseTheItemLocationAndFormatTheIdentityOnce() {
            final TrackingCapabilityProvider provider = deepChainProvider();
            final ChangeTracker tracker = new ChangeTracker(provider.create());
            final Warehouse warehouse = warehouse();
            tracker.track(warehouse);
            final Crate crate = warehouse.shelves.get(0).crates.get(0);
            crate.weight = 2;
            crate.label = "c2";

            CountingId.TO_STRING_CALLS.set(0);
            final ChangeSet changeSet = tracker.calculateChanges();

            assertThat(changeSet.getLeafChanges()).extracting(Change::fullPath).containsExactly(
                    "shelves[1].crates[id[C1]].weight",
                    "shelves[1].crates[id[C1]].label");
            assertThat(CountingId.TO_STRING_CALLS).hasValue(1);

            final Change crateContainer = changeWithPath(changeSet, "shelves[1].crates[id[C1]]");
            for (final Change leaf : changeSet.getLeafChanges()) {
                assertThat(ChangeLocation.field(crateContainer.location(), leaf.fieldName()))
                        .isEqualTo(leaf.location());
            }
        }

        @Test
        @DisplayName("重复读取变更路径不重建文本，也不重新格式化标识")
        void repeatedPathReads_shouldNotRebuildTextNorReformatTheIdentity() {
            final TrackingCapabilityProvider provider = deepChainProvider();
            final ChangeTracker tracker = new ChangeTracker(provider.create());
            final Warehouse warehouse = warehouse();
            tracker.track(warehouse);
            warehouse.shelves.get(0).crates.get(0).weight = 2;

            final ChangeSet changeSet = tracker.calculateChanges();
            final Change leaf = changeSet.getLeafChanges().get(0);
            CountingId.TO_STRING_CALLS.set(0);

            for (int read = 0; read < 8; read++) {
                assertThat(leaf.path()).isEqualTo("shelves[1].crates[id[C1]].weight");
                assertThat(leaf.fullPath()).isEqualTo("shelves[1].crates[id[C1]].weight");
                assertThat(leaf.relativePath()).isEqualTo("weight");
                assertThat(changeSet.getAllChanges()).hasSize(5);
                assertThat(changeSet.getLeafChanges()).hasSize(1);
            }

            assertThat(CountingId.TO_STRING_CALLS.get()).isZero();
        }

        @Test
        @DisplayName("深链零变更遍历不格式化任何标识文本")
        void zeroChangeOnTheDeepChain_shouldNotFormatAnyIdentityText() {
            final TrackingCapabilityProvider provider = deepChainProvider();
            final ChangeTracker tracker = new ChangeTracker(provider.create());
            final Warehouse warehouse = warehouse();
            warehouse.shelves.get(0).crates.add(new Crate(new CountingId("C2")));
            tracker.track(warehouse);
            CountingId.TO_STRING_CALLS.set(0);

            final ChangeSet changeSet = tracker.calculateChanges();

            assertThat(changeSet.isEmpty()).isTrue();
            assertThat(CountingId.TO_STRING_CALLS.get()).isZero();
        }

        @Test
        @DisplayName("深链基线往返后同一变更路径与定位取值保持不变")
        void deepChainBaselineRoundTrip_shouldKeepTheSamePathAndLocation() {
            final TrackingCapabilityProvider provider = deepChainProvider();
            final ChangeTracker tracker = new ChangeTracker(provider.create());
            final Warehouse warehouse = warehouse();
            tracker.track(warehouse);
            warehouse.shelves.get(0).crates.get(0).weight = 2;

            CountingId.TO_STRING_CALLS.set(0);
            final Change before = tracker.calculateChanges().getLeafChanges().get(0);
            final int callsBefore = CountingId.TO_STRING_CALLS.get();
            final BaselineSnapshot baseline = tracker.captureBaseline();

            CountingId.TO_STRING_CALLS.set(0);
            final ChangeTracker restored = ChangeTracker.fromBaseline(provider.create(), baseline);
            final Change after = restored.calculateChanges().getLeafChanges().get(0);
            final int callsAfter = CountingId.TO_STRING_CALLS.get();

            assertThat(after.path()).isEqualTo(before.path());
            assertThat(after.location()).isEqualTo(before.location());
            assertThat(callsAfter).isEqualTo(callsBefore);
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
     * 深链装配探针的根对象：含标量字段与集合字段。
     */
    static class Warehouse {

        /** 根层标量字段。 */
        String status = "OPEN";

        /** 集合字段。 */
        List<Shelf> shelves = new ArrayList<>();
    }

    /**
     * 深链装配探针的中间集合项：含标量字段与嵌套集合字段。
     */
    static class Shelf {

        /** 以 {@link Long} 承载的业务标识。 */
        Long id;

        /** 集合项内的标量字段。 */
        String code = "s";

        /** 嵌套集合字段。 */
        List<Crate> crates = new ArrayList<>();

        /**
         * 创建货架探针。
         *
         * @param id 业务标识
         */
        Shelf(final Long id) {
            this.id = id;
        }
    }

    /**
     * 深链装配探针的最深集合项：含可变标量字段与计数文本业务标识。
     */
    static class Crate {

        /** 以可计数文本类型承载的业务标识。 */
        CountingId id;

        /** 最深层的标量字段。 */
        int weight = 1;

        /** 最深层的另一标量字段。 */
        String label = "c";

        /**
         * 创建货箱探针。
         *
         * @param id 业务标识
         */
        Crate(final CountingId id) {
            this.id = id;
        }
    }

    /**
     * 集成测试的不可变业务标识探针：{@code equals}/{@code hashCode} 基于值，
     * {@code toString} 计入调用次数，用于验证标识文本在该活动项内只准备一次。
     */
    static final class CountingId {

        /** {@code toString} 调用计数。 */
        static final AtomicInteger TO_STRING_CALLS = new AtomicInteger();

        /** 值。 */
        private final String value;

        /**
         * 创建标识。
         *
         * @param value 值
         */
        CountingId(final String value) {
            this.value = value;
        }

        /**
         * 按值比较。
         *
         * @param other 待比较对象
         * @return 值相同返回 true
         */
        @Override
        public boolean equals(final Object other) {
            return other instanceof CountingId that && this.value.equals(that.value);
        }

        /**
         * 值哈希。
         *
         * @return 值哈希
         */
        @Override
        public int hashCode() {
            return this.value.hashCode();
        }

        /**
         * 计数并返回文本。
         *
         * @return 文本表示
         */
        @Override
        public String toString() {
            TO_STRING_CALLS.incrementAndGet();
            return "id[" + this.value + "]";
        }
    }

    /**
     * 经 ServiceLoader 发现默认 provider 并注册深链两侧业务标识。
     *
     * @return 注册了货架与货箱标识提取器的默认 provider
     */
    private static TrackingCapabilityProvider deepChainProvider() {
        return defaultProvider()
                .withIdentifier(Shelf.class, shelf -> shelf.id)
                .withIdentifier(Crate.class, crate -> crate.id);
    }

    /**
     * 建立三层嵌套、两层集合项的仓库探针。
     *
     * @return 含一个货架与一个货箱的仓库探针
     */
    private static Warehouse warehouse() {
        final Warehouse warehouse = new Warehouse();
        final Shelf shelf = new Shelf(1L);
        shelf.crates.add(new Crate(new CountingId("C1")));
        warehouse.shelves.add(shelf);
        return warehouse;
    }

    /**
     * 按完整路径从变更集完整视图中取唯一变更。
     *
     * @param changeSet 变更集
     * @param fullPath  完整路径
     * @return 该路径对应的变更
     * @throws IllegalStateException 如果匹配到的变更数不为 1
     */
    private static Change changeWithPath(final ChangeSet changeSet, final String fullPath) {
        final List<Change> matched = changeSet.getAllChanges().stream()
                .filter(change -> fullPath.equals(change.fullPath()))
                .toList();
        if (matched.size() != 1) {
            throw new IllegalStateException("Expected exactly one change at " + fullPath
                    + " but found " + matched.size());
        }
        return matched.get(0);
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
