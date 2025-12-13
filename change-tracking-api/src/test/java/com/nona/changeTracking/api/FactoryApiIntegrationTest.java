package com.nona.changeTracking.api;

import com.nona.changeTracking.domain.model.changeset.*;
import com.nona.changeTracking.domain.model.unitofwork.UnitOfWork;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 使用公开 API 的端到端测试。
 * <p>
 * 测试通过 {@link UnitOfWorkFactory} 创建的 UnitOfWork 的基本功能。
 * <p>
 * 注意：需要配置业务标识符或自定义值类型的端到端测试位于 core 模块，
 * 因为这些配置需要直接访问 Provider 实现类。
 *
 * @see com.nona.changeTracking.internal.capability.EndToEndIntegrationTest
 */
@DisplayName("Factory API 端到端测试")
class FactoryApiIntegrationTest {

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
    }

    static class LineItem {
        private Long id;
        private String productName;
        private int quantity;

        LineItem(Long id, String productName, int quantity) {
            this.id = id;
            this.productName = productName;
            this.quantity = quantity;
        }

        Long getId() { return id; }
        String getProductName() { return productName; }
        int getQuantity() { return quantity; }
        void setQuantity(int quantity) { this.quantity = quantity; }
    }

    // ==================== 使用 Factory API 的测试 ====================

    @Nested
    @DisplayName("基本变更检测")
    class BasicChangeDetection {

        @Test
        @DisplayName("通过 UnitOfWorkFactory 创建的 UnitOfWork 应能正常工作")
        void factoryCreatedUow_shouldWorkCorrectly() {
            UnitOfWork uow = UnitOfWorkFactory.builder()
                    .withDefaults()
                    .build();

            Order order = new Order(1L, "ORD-001");
            uow.registerClean(order);

            order.setStatus("SHIPPED");

            ChangeSet changeSet = uow.calculateChanges();

            assertThat(changeSet.isEmpty()).isFalse();
            assertThat(changeSet.getLeafChanges().stream()
                    .filter(c -> c instanceof FieldChange)
                    .map(c -> (FieldChange) c)
                    .anyMatch(fc -> fc.path().equals("status") &&
                            fc.oldValue().equals("PENDING") &&
                            fc.newValue().equals("SHIPPED")))
                    .isTrue();
        }

        @Test
        @DisplayName("未修改对象不应产生变更")
        void unmodifiedObject_shouldNotProduceChanges() {
            UnitOfWork uow = UnitOfWorkFactory.builder()
                    .withDefaults()
                    .build();

            Order order = new Order(1L, "ORD-001");
            uow.registerClean(order);

            // 不做任何修改

            ChangeSet changeSet = uow.calculateChanges();

            assertThat(changeSet.isEmpty()).isTrue();
        }
    }

    @Nested
    @DisplayName("集合变更检测")
    class CollectionChangeDetection {

        @Test
        @DisplayName("应能检测集合项新增")
        void shouldDetectItemAdded() {
            UnitOfWork uow = UnitOfWorkFactory.builder()
                    .withDefaults()
                    .build();

            Order order = new Order(1L, "ORD-001");
            uow.registerClean(order);

            order.addItem(new LineItem(1L, "商品A", 2));

            ChangeSet changeSet = uow.calculateChanges();

            assertThat(changeSet.isEmpty()).isFalse();
            assertThat(changeSet.getLeafChanges().stream()
                    .anyMatch(c -> c instanceof ItemAddedChange))
                    .isTrue();
        }

        @Test
        @DisplayName("应能检测集合项属性修改")
        void shouldDetectItemModified() {
            UnitOfWork uow = UnitOfWorkFactory.builder()
                    .withDefaults()
                    .build();

            LineItem item = new LineItem(1L, "商品A", 2);
            Order order = new Order(1L, "ORD-001");
            order.addItem(item);
            uow.registerClean(order);

            item.setQuantity(5);

            ChangeSet changeSet = uow.calculateChanges();

            assertThat(changeSet.isEmpty()).isFalse();
            assertThat(changeSet.getLeafChanges().stream()
                    .filter(c -> c instanceof FieldChange)
                    .map(c -> (FieldChange) c)
                    .anyMatch(fc -> fc.path().contains("quantity")))
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("多对象追踪")
    class MultipleObjectTracking {

        @Test
        @DisplayName("应能同时追踪多个对象")
        void shouldTrackMultipleObjects() {
            UnitOfWork uow = UnitOfWorkFactory.builder()
                    .withDefaults()
                    .build();

            Order order1 = new Order(1L, "ORD-001");
            Order order2 = new Order(2L, "ORD-002");
            uow.registerClean(order1);
            uow.registerClean(order2);

            order1.setStatus("CONFIRMED");
            order2.setStatus("SHIPPED");

            ChangeSet changeSet = uow.calculateChanges();

            assertThat(changeSet.changes()).hasSize(2);
        }
    }
}
