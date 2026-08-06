package com.nona.changeTracking.internal.capability;

import com.nona.changeTracking.domain.model.changeset.*;
import com.nona.changeTracking.domain.model.unitofwork.UnitOfWork;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端集成测试。
 * <p>
 * 测试从 Provider 配置到变更检测的完整流程。
 * <p>
 * 注意：此测试位于 core 模块，因为它需要直接访问 internal 包中的
 * {@link DefaultTrackingCapabilityProvider} 类。
 */
@DisplayName("端到端集成测试")
class EndToEndIntegrationTest {

    // ==================== 测试领域模型 ====================

    static class Order {
        private Long id;
        private String orderNumber;
        private String status;
        private List<LineItem> items = new ArrayList<>();

        Order(Long id, String orderNumber) {
            this.id = id;
            this.orderNumber = orderNumber;
            this.status = "PENDING";
        }

        Long getId() { return id; }
        String getOrderNumber() { return orderNumber; }
        String getStatus() { return status; }
        void setStatus(String status) { this.status = status; }
        List<LineItem> getItems() { return items; }
        void addItem(LineItem item) { items.add(item); }
        void removeItem(LineItem item) { items.remove(item); }
    }

    static class LineItem {
        private Long id;
        private String sku;
        private String productName;
        private int quantity;
        private Money price;

        LineItem(Long id, String sku, String productName, int quantity, Money price) {
            this.id = id;
            this.sku = sku;
            this.productName = productName;
            this.quantity = quantity;
            this.price = price;
        }

        Long getId() { return id; }
        String getSku() { return sku; }
        String getProductName() { return productName; }
        int getQuantity() { return quantity; }
        void setQuantity(int quantity) { this.quantity = quantity; }
        Money getPrice() { return price; }
        void setPrice(Money price) { this.price = price; }
    }

    static class Money {
        private final BigDecimal amount;
        private final String currency;

        Money(BigDecimal amount, String currency) {
            this.amount = amount;
            this.currency = currency;
        }

        BigDecimal getAmount() { return amount; }
        String getCurrency() { return currency; }

        @Override
        public String toString() {
            return amount + " " + currency;
        }
    }

    // ==================== 业务标识符配置测试 ====================

    @Nested
    @DisplayName("业务标识符配置端到端测试")
    class BusinessIdentifierE2ETest {

        @Test
        @DisplayName("使用业务标识符后，集合项重排序不应产生虚假变更")
        void withIdentifier_collectionReorder_shouldNotProduceFalseChanges() {
            // 配置业务标识符
            DefaultTrackingCapabilityProvider provider = new DefaultTrackingCapabilityProvider();
            provider.withIdentifier(LineItem.class, LineItem::getId);

            UnitOfWork uow = new UnitOfWork(provider.create());

            // 创建订单
            Order order = new Order(1L, "ORD-001");
            LineItem item1 = new LineItem(100L, "SKU-A", "商品A", 2, new Money(new BigDecimal("100.00"), "CNY"));
            LineItem item2 = new LineItem(200L, "SKU-B", "商品B", 1, new Money(new BigDecimal("200.00"), "CNY"));
            order.addItem(item1);
            order.addItem(item2);

            uow.registerClean(order);

            // 只重排序（交换位置），不修改内容
            order.getItems().clear();
            order.addItem(item2);
            order.addItem(item1);

            ChangeSet changeSet = uow.calculateChanges();

            // 使用业务标识符匹配，重排序不应产生变更
            assertThat(changeSet.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("使用业务标识符后，应能正确检测集合项的新增和删除")
        void withIdentifier_addAndRemoveItems_shouldDetectCorrectly() {
            DefaultTrackingCapabilityProvider provider = new DefaultTrackingCapabilityProvider();
            provider.withIdentifier(LineItem.class, LineItem::getId);

            UnitOfWork uow = new UnitOfWork(provider.create());

            Order order = new Order(1L, "ORD-001");
            LineItem item1 = new LineItem(100L, "SKU-A", "商品A", 2, new Money(new BigDecimal("100.00"), "CNY"));
            LineItem item2 = new LineItem(200L, "SKU-B", "商品B", 1, new Money(new BigDecimal("200.00"), "CNY"));
            order.addItem(item1);
            order.addItem(item2);

            uow.registerClean(order);

            // 删除 item1，新增 item3
            order.removeItem(item1);
            LineItem item3 = new LineItem(300L, "SKU-C", "商品C", 3, new Money(new BigDecimal("300.00"), "CNY"));
            order.addItem(item3);

            ChangeSet changeSet = uow.calculateChanges();

            List<Change> leafChanges = changeSet.getLeafChanges();

            // 应检测到一个删除和一个新增
            assertThat(leafChanges.stream().filter(c -> c instanceof ItemRemovedChange).count()).isEqualTo(1);
            assertThat(leafChanges.stream().filter(c -> c instanceof ItemAddedChange).count()).isEqualTo(1);
        }

        @Test
        @DisplayName("使用业务标识符后，应能正确检测集合项的属性修改")
        void withIdentifier_modifyItemProperty_shouldDetectCorrectly() {
            DefaultTrackingCapabilityProvider provider = new DefaultTrackingCapabilityProvider();
            provider.withIdentifier(LineItem.class, LineItem::getId);

            UnitOfWork uow = new UnitOfWork(provider.create());

            Order order = new Order(1L, "ORD-001");
            LineItem item = new LineItem(100L, "SKU-A", "商品A", 2, new Money(new BigDecimal("100.00"), "CNY"));
            order.addItem(item);

            uow.registerClean(order);

            // 修改行项目的数量
            item.setQuantity(5);

            ChangeSet changeSet = uow.calculateChanges();

            List<Change> leafChanges = changeSet.getLeafChanges();

            // 应检测到字段变更
            assertThat(leafChanges.stream()
                    .filter(c -> c instanceof ValueChange)
                    .map(c -> (ValueChange) c)
                    .anyMatch(fc -> fc.path().contains("quantity") &&
                            fc.oldValue().equals(2) &&
                            fc.newValue().equals(5)))
                    .isTrue();
        }
    }

    // ==================== 自定义值类型测试 ====================

    @Nested
    @DisplayName("自定义值类型端到端测试")
    class CustomValueTypeE2ETest {

        @Test
        @DisplayName("配置 Money 为值类型后，整体替换应产生单个字段变更")
        void withValueType_replaceValue_shouldProduceSingleValueChange() {
            DefaultTrackingCapabilityProvider provider = new DefaultTrackingCapabilityProvider();
            provider.withValueType(Money.class);

            UnitOfWork uow = new UnitOfWork(provider.create());

            Order order = new Order(1L, "ORD-001");
            Money originalPrice = new Money(new BigDecimal("100.00"), "CNY");
            LineItem item = new LineItem(100L, "SKU-A", "商品A", 2, originalPrice);
            order.addItem(item);

            uow.registerClean(order);

            // 替换整个 Money 对象
            Money newPrice = new Money(new BigDecimal("150.00"), "CNY");
            item.setPrice(newPrice);

            ChangeSet changeSet = uow.calculateChanges();

            List<Change> leafChanges = changeSet.getLeafChanges();

            // Money 作为值类型，应该产生 price 字段的变更，而非 amount/currency 的变更
            assertThat(leafChanges.stream()
                    .filter(c -> c instanceof ValueChange)
                    .map(c -> (ValueChange) c)
                    .anyMatch(fc -> fc.path().contains("price")))
                    .isTrue();
        }
    }

    // ==================== 完整订单场景测试 ====================

    @Nested
    @DisplayName("完整订单场景测试")
    class CompleteOrderScenarioTest {

        @Test
        @DisplayName("订单状态变更 + 行项目增删改的综合场景")
        void complexOrderChanges_shouldDetectAllChanges() {
            DefaultTrackingCapabilityProvider provider = new DefaultTrackingCapabilityProvider();
            provider.withIdentifier(LineItem.class, LineItem::getId)
                    .withValueType(Money.class);

            UnitOfWork uow = new UnitOfWork(provider.create());

            // 创建初始订单
            Order order = new Order(1L, "ORD-001");
            LineItem item1 = new LineItem(100L, "SKU-A", "商品A", 2, new Money(new BigDecimal("100.00"), "CNY"));
            LineItem item2 = new LineItem(200L, "SKU-B", "商品B", 1, new Money(new BigDecimal("200.00"), "CNY"));
            order.addItem(item1);
            order.addItem(item2);

            uow.registerClean(order);

            // 执行多种变更：
            // 1. 修改订单状态
            order.setStatus("CONFIRMED");
            // 2. 修改 item1 的数量
            item1.setQuantity(5);
            // 3. 删除 item2
            order.removeItem(item2);
            // 4. 新增 item3
            LineItem item3 = new LineItem(300L, "SKU-C", "商品C", 3, new Money(new BigDecimal("300.00"), "CNY"));
            order.addItem(item3);

            ChangeSet changeSet = uow.calculateChanges();

            List<Change> leafChanges = changeSet.getLeafChanges();

            // 验证订单状态变更
            assertThat(leafChanges.stream()
                    .filter(c -> c instanceof ValueChange)
                    .map(c -> (ValueChange) c)
                    .anyMatch(fc -> fc.path().equals("status")))
                    .isTrue();

            // 验证 item1 数量变更
            assertThat(leafChanges.stream()
                    .filter(c -> c instanceof ValueChange)
                    .map(c -> (ValueChange) c)
                    .anyMatch(fc -> fc.path().contains("quantity")))
                    .isTrue();

            // 验证 item2 删除
            assertThat(leafChanges.stream()
                    .filter(c -> c instanceof ItemRemovedChange)
                    .count())
                    .isEqualTo(1);

            // 验证 item3 新增
            assertThat(leafChanges.stream()
                    .filter(c -> c instanceof ItemAddedChange)
                    .count())
                    .isEqualTo(1);
        }
    }
}
