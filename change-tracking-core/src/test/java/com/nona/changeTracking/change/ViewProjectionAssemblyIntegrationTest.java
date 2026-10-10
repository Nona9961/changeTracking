package com.nona.changeTracking.change;

import com.nona.changeTracking.tracking.ChangeTracker;
import com.nona.changeTracking.tracking.DefaultTrackingCapabilityProvider;
import com.nona.changeTracking.tracking.TrackingCapabilityProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 视图投影的装配面集成测试：用真实 SPI 装配的默认 provider、真实 capability、真实快照与比较策略
 * 以及真实 {@link ChangeTracker} 链路，验证单元测试（手工构造变更树）覆盖不到的投影接入面。
 * <p>
 * 覆盖：默认装配链路产出的变更集在两个视图上的输出契约（所有入口定位一致）、经公开扩展点注册业务标识
 * 后的集合项上下文、重复获取与值语义、以及视图消费不得改动比较结果的基线语义。
 */
@DisplayName("视图投影装配面集成测试")
class ViewProjectionAssemblyIntegrationTest {

    /** 每个用例重建链路的 provider，避免配置与状态在用例之间串用。 */
    private TrackingCapabilityProvider provider;

    @BeforeEach
    void setUp() {
        provider = defaultProvider();
    }

    @Nested
    @DisplayName("默认装配链路的两个视图")
    class DefaultAssemblyViews {

        @Test
        @DisplayName("标量变更经默认链路应在完整视图与叶子视图各出现一次且路径一致")
        void scalarChange_shouldAppearOnceInBothViews() {
            final ChangeTracker tracker = new ChangeTracker(provider.create());
            final Order order = new Order();
            tracker.track(order);
            order.status = "PAID";

            final ChangeSet changeSet = tracker.calculateChanges();
            final List<Change> allChanges = changeSet.getAllChanges();
            final List<Change> leafChanges = changeSet.getLeafChanges();

            assertThat(allChanges).hasSize(1);
            assertThat(leafChanges).hasSize(1);
            assertThat(allChanges.get(0)).isInstanceOf(ValueChange.class);
            assertThat(leafChanges.get(0)).isInstanceOf(ValueChange.class);
            assertThat(allChanges.get(0).path()).isEqualTo("status");
            assertThat(leafChanges.get(0).path()).isEqualTo("status");
            assertThat(leafChanges.get(0).path()).isEqualTo(leafChanges.get(0).fullPath());
        }

        @Test
        @DisplayName("零变更经默认链路应返回空的两个视图")
        void noChange_shouldReturnEmptyViews() {
            final ChangeTracker tracker = new ChangeTracker(provider.create());
            final Order order = new Order();
            tracker.track(order);

            final ChangeSet changeSet = tracker.calculateChanges();

            assertThat(changeSet.isEmpty()).isTrue();
            assertThat(changeSet.getAllChanges()).isEmpty();
            assertThat(changeSet.getLeafChanges()).isEmpty();
        }

        @Test
        @DisplayName("集合增删经默认链路：完整视图含容器，叶子视图只含增删项")
        void collectionAddition_shouldProjectContainerAndLeaves() {
            final ChangeTracker tracker = new ChangeTracker(provider.create());
            final Order order = new Order();
            order.items.add(new LineItem(1L, "SKU-1"));
            tracker.track(order);
            order.items.add(new LineItem(2L, "SKU-2"));

            final ChangeSet changeSet = tracker.calculateChanges();
            final List<Change> allChanges = changeSet.getAllChanges();
            final List<Change> leafChanges = changeSet.getLeafChanges();

            assertThat(allChanges).anyMatch(change -> change instanceof ContainerChange);
            assertThat(leafChanges).noneMatch(change -> change instanceof ContainerChange);
            assertThat(leafChanges).allMatch(change -> change instanceof ItemAddedChange);
            assertThat(leafChanges).hasSize(1);
            // 完整视图的容器保留嵌套子视图：容器 children 非空，且子条目的完整路径仍指向集合项
            final ContainerChange container = allChanges.stream()
                    .filter(ContainerChange.class::isInstance)
                    .map(ContainerChange.class::cast)
                    .findFirst()
                    .orElseThrow();
            assertThat(container.children()).isNotEmpty();
            assertThat(container.children()).extracting(Change::fullPath)
                    .allMatch(fullPath -> fullPath.startsWith("items["));
        }

        @Test
        @DisplayName("重复获取同一变更集的两个视图应等值，且不改变比较结果的基线语义")
        void repeatedAcquisition_shouldKeepValueSemanticsAndTheBaseline() {
            final ChangeTracker tracker = new ChangeTracker(provider.create());
            final Order order = new Order();
            tracker.track(order);
            order.status = "PAID";

            final ChangeSet changeSet = tracker.calculateChanges();
            final List<Change> firstAll = changeSet.getAllChanges();
            final List<Change> secondAll = changeSet.getAllChanges();
            final List<Change> firstLeaves = changeSet.getLeafChanges();
            final List<Change> secondLeaves = changeSet.getLeafChanges();

            assertThat(secondAll).isEqualTo(firstAll);
            assertThat(secondLeaves).isEqualTo(firstLeaves);
            // 视图消费不推进基线：再次计算仍报同一条变更
            assertThat(tracker.calculateChanges().getLeafChanges()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("业务标识配置下的集合上下文")
    class RegisteredIdentifierContext {

        @Test
        @DisplayName("注册业务标识后，两视图对同一集合项内字段节点给出一致的定位")
        void registeredIdentifier_shouldExposeTheSameLocationInBothViews() {
            provider.withIdentifier(LineItem.class, item -> item.id);
            final ChangeTracker tracker = new ChangeTracker(provider.create());
            final Order order = new Order();
            order.items.add(new LineItem(7L, "SKU-7"));
            tracker.track(order);
            order.items.get(0).sku = "SKU-7-changed";

            final ChangeSet changeSet = tracker.calculateChanges();
            final Change allEntry = changeSet.getAllChanges().stream()
                    .filter(change -> change.fullPath().equals("items[7].sku"))
                    .findFirst()
                    .orElseThrow();
            final Change leafEntry = changeSet.getLeafChanges().stream()
                    .filter(change -> change.fullPath().equals("items[7].sku"))
                    .findFirst()
                    .orElseThrow();

            assertThat(allEntry.collectionFieldName()).isEqualTo("items");
            assertThat(leafEntry.collectionFieldName()).isEqualTo("items");
            assertThat(allEntry.fieldName()).isEqualTo("sku");
            assertThat(leafEntry.fieldName()).isEqualTo("sku");
            assertThat(allEntry.isParentCollection()).isFalse();
            assertThat(leafEntry.isParentCollection()).isFalse();
        }

        @Test
        @DisplayName("注册业务标识后集合项新增在两个视图中给出一致的定位")
        void registeredIdentifier_addition_shouldKeepTheSameLocationInBothViews() {
            provider.withIdentifier(LineItem.class, item -> item.id);
            final ChangeTracker tracker = new ChangeTracker(provider.create());
            final Order order = new Order();
            tracker.track(order);
            order.items.add(new LineItem(100L, "SKU-100"));

            final ChangeSet changeSet = tracker.calculateChanges();
            final Change allEntry = changeSet.getAllChanges().stream()
                    .filter(change -> change instanceof ItemAddedChange)
                    .findFirst()
                    .orElseThrow();
            final Change leafEntry = changeSet.getLeafChanges().stream()
                    .filter(change -> change instanceof ItemAddedChange)
                    .findFirst()
                    .orElseThrow();

            assertThat(allEntry.path()).isEqualTo("items[100]");
            assertThat(leafEntry.path()).isEqualTo("items[100]");
            // 两个入口的定位一致：直接集合项的 fieldName 为 null、集合归属为 items、直接父级为集合。
            assertThat(allEntry.fieldName()).isNull();
            assertThat(allEntry.collectionFieldName()).isEqualTo("items");
            assertThat(allEntry.isParentCollection()).isTrue();
            assertThat(leafEntry.fieldName()).isNull();
            assertThat(leafEntry.collectionFieldName()).isEqualTo("items");
            assertThat(leafEntry.isParentCollection()).isTrue();
        }

        @Test
        @DisplayName("完整视图容器 children 中集合项容器的字段变更应继承 items 上下文")
        void registeredIdentifier_containerChildFieldShouldCarryFullPathsAndInheritedContext() {
            provider.withIdentifier(LineItem.class, item -> item.id);
            final ChangeTracker tracker = new ChangeTracker(provider.create());
            final Order order = new Order();
            order.items.add(new LineItem(7L, "SKU-7"));
            tracker.track(order);
            order.items.get(0).sku = "SKU-7-changed";

            final ChangeSet changeSet = tracker.calculateChanges();

            // 真实比较产出的树：items 容器 → 集合项容器 [7] → 字段 sku
            final Change itemsContainer = changeSet.getAllChanges().stream()
                    .filter(change -> change.fullPath().equals("items"))
                    .findFirst()
                    .orElseThrow();
            final Change itemContainer = ((ContainerChange) itemsContainer).children().stream()
                    .filter(change -> change.fullPath().equals("items[7]"))
                    .findFirst()
                    .orElseThrow();
            final Change skuWithinContainer = ((ContainerChange) itemContainer).children().stream()
                    .filter(change -> change.fullPath().equals("items[7].sku"))
                    .findFirst()
                    .orElseThrow();

            assertThat(itemContainer.relativePath()).isEqualTo("[7]");
            assertThat(skuWithinContainer.relativePath()).isEqualTo("sku");

            assertThat(itemContainer.collectionFieldName()).isEqualTo("items");
            assertThat(itemContainer.isParentCollection()).isTrue();
            assertThat(skuWithinContainer.fieldName()).isEqualTo("sku");
            assertThat(skuWithinContainer.collectionFieldName()).isEqualTo("items");
            assertThat(skuWithinContainer.isParentCollection()).isFalse();
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
         * @param id  业务标识
         * @param sku 商品编码
         */
        LineItem(final Long id, final String sku) {
            this.id = id;
            this.sku = sku;
        }
    }

    /**
     * 经 ServiceLoader 发现默认 provider。
     *
     * @return 默认 provider 实例
     * @throws IllegalStateException 如果未发现默认 provider
     */
    private static TrackingCapabilityProvider defaultProvider() {
        for (final TrackingCapabilityProvider candidate : ServiceLoader.load(TrackingCapabilityProvider.class)) {
            if (DefaultTrackingCapabilityProvider.class.isInstance(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Default provider not discovered through ServiceLoader");
    }
}
