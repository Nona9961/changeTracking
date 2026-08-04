package com.nona.changeTracking.internal.snapshot;

import com.nona.changeTracking.domain.capability.TrackingConfiguration;
import com.nona.changeTracking.domain.capability.ValueNodeComparisonStrategy;
import com.nona.changeTracking.domain.model.changeset.ChangeNode;
import com.nona.changeTracking.domain.model.changeset.ContainerChangeNode;
import com.nona.changeTracking.domain.model.changeset.FieldChangeNode;
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
 * 现状无缓存（反射零缓存），此测试为后续优化（P1/P2/P3 已延后）提供性能基准守护。
 */
@DisplayName("ValueNodeSnapshotStrategy 大规模快照与比较性能特征测试")
class ValueNodeSnapshotStrategyPerformanceTest {

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

        final ChangeNode result = assertTimeout(
                Duration.ofSeconds(10),
                () -> comparison.compare(oldSnapshot, newSnapshot)
        );

        // 正确性：万级 items 中仅第 1000 项 quantity 变化 → 只报告一处变更
        final ContainerChangeNode rootChange = (ContainerChangeNode) result;
        assertEquals(1, rootChange.children().size());
        final ContainerChangeNode itemsChange = (ContainerChangeNode) rootChange.children().get(0);
        assertEquals("items", itemsChange.path());
        assertEquals(1, itemsChange.children().size());
        final ContainerChangeNode itemChange = (ContainerChangeNode) itemsChange.children().get(0);
        assertEquals("items[1000]", itemChange.path());
        assertEquals(1, itemChange.children().size());
        final FieldChangeNode quantityChange = (FieldChangeNode) itemChange.children().get(0);
        assertEquals("items[1000].quantity", quantityChange.path());
        assertEquals(0, quantityChange.oldValue());
        assertEquals(999, quantityChange.newValue());
    }
}