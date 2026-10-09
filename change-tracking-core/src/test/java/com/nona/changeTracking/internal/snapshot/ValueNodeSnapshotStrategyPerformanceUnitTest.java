package com.nona.changeTracking.internal.snapshot;

import com.nona.changeTracking.domain.capability.TrackingConfiguration;
import com.nona.changeTracking.domain.capability.ValueNodeComparisonStrategy;
import com.nona.changeTracking.domain.model.changeset.Change;
import com.nona.changeTracking.domain.model.changeset.ContainerChange;
import com.nona.changeTracking.domain.model.changeset.ValueChange;
import com.nona.changeTracking.domain.model.snapshot.ValueNodeSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeout;

/**
 * 大规模快照与比较的性能特征测试。
 * <p>
 * 特征测试：断言万级 items 快照/比较在宽松时间预算内完成（防 flaky），
 * 同时验证比较结果正确性（单点变更只报告一处）。
 * <p>
 * 类型元数据与配置规则已按类复用（本策略持有共享的 {@link ReflectionMetadataCache} 与
 * {@link ConfiguredTypeRulesCache}），本测试只守护万级负载的时间上限与单点变更正确性，
 * 不作为缓存收益的基准对照。
 */
@DisplayName("ValueNodeSnapshotStrategy 大规模快照与比较性能特征测试")
class ValueNodeSnapshotStrategyPerformanceUnitTest {

    static class OrderItem {
        Long id;
        String sku;
        int quantity;

        OrderItem(Long id, String sku, int quantity) {
            this.id = id;
            this.sku = sku;
            this.quantity = quantity;
        }
    }

    static class Order {
        Long id;
        String orderNumber;
        List<OrderItem> items;

        Order(Long id, String orderNumber, List<OrderItem> items) {
            this.id = id;
            this.orderNumber = orderNumber;
            this.items = items;
        }
    }

    private static final int ITEM_COUNT = 10_000;

    /**
     * 构建指定规模的 Order（含 items），用于快照/比较测试。
     *
     * @param itemCount           items 数量。
     * @param mutatedQuantityIndex 需要修改 quantity 的 item 下标；-1 表示全部使用默认 quantity。
     * @return 构建的 Order。
     */
    private static Order buildOrder(final int itemCount, final int mutatedQuantityIndex) {
        final List<OrderItem> items = new ArrayList<>(itemCount);
        for (int i = 0; i < itemCount; i++) {
            final int quantity = (i == mutatedQuantityIndex) ? 999 : i % 10;
            items.add(new OrderItem((long) i, "SKU-" + i, quantity));
        }
        return new Order(1L, "ORD-1", items);
    }

    @Test
    @DisplayName("万级 items 对象快照应在 10 秒内完成")
    void snapshot_withTenThousandItems_shouldCompleteWithinBudget() {
        final ValueNodeSnapshotStrategy strategy = new ValueNodeSnapshotStrategy(TrackingConfiguration.empty());
        final Order order = buildOrder(ITEM_COUNT, -1);

        assertTimeout(Duration.ofSeconds(10), () -> strategy.createSnapshot(order));
    }

    @Test
    @DisplayName("万级 items 集合比较应在 10 秒内完成且正确报告单点变更")
    void compare_withTenThousandItems_shouldCompleteWithinBudgetAndReportSingleChange() {
        final Map<Class<?>, Function<Object, Object>> extractors = new HashMap<>();
        extractors.put(OrderItem.class, obj -> ((OrderItem) obj).id);
        final TrackingConfiguration config = new TrackingConfiguration(
                extractors,
                Collections.emptySet(),
                Collections.emptySet()
        );
        final ValueNodeSnapshotStrategy strategy = new ValueNodeSnapshotStrategy(config);
        final ValueNodeComparisonStrategy comparison = new ValueNodeComparisonStrategy();

        final Order oldOrder = buildOrder(ITEM_COUNT, -1);
        final Order newOrder = buildOrder(ITEM_COUNT, 1_000);

        // 快照在断言外执行，仅比较操作纳入时间预算
        final ValueNodeSnapshot oldSnapshot = strategy.createSnapshot(oldOrder);
        final ValueNodeSnapshot newSnapshot = strategy.createSnapshot(newOrder);

        final List<Change> result = assertTimeout(
                Duration.ofSeconds(10),
                () -> comparison.compare(oldSnapshot, newSnapshot)
        );

        // 正确性：万级 items 中仅第 1000 项 quantity 变化 → 只报告一处变更
        assertEquals(1, result.size());
        final ContainerChange itemsChange = (ContainerChange) result.get(0);
        assertEquals("items", itemsChange.fullPath());
        assertEquals(1, itemsChange.children().size());
        final ContainerChange itemChange = (ContainerChange) itemsChange.children().get(0);
        assertEquals("items[1000]", itemChange.fullPath());
        assertEquals(1, itemChange.children().size());
        final ValueChange quantityChange = (ValueChange) itemChange.children().get(0);
        assertEquals("items[1000].quantity", quantityChange.fullPath());
        assertEquals(0, quantityChange.oldValue());
        assertEquals(999, quantityChange.newValue());
    }
}