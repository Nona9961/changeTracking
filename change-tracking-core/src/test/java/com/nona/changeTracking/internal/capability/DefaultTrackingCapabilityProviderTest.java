package com.nona.changeTracking.internal.capability;

import com.nona.changeTracking.domain.capability.ComparisonStrategy;
import com.nona.changeTracking.domain.capability.TrackingCapability;
import com.nona.changeTracking.domain.capability.ValueNodeComparisonStrategy;
import com.nona.changeTracking.domain.model.changeset.FieldChange;
import com.nona.changeTracking.domain.model.changeset.ItemAddedChange;
import com.nona.changeTracking.domain.model.unitofwork.UnitOfWork;
import com.nona.changeTracking.internal.snapshot.ValueNodeSnapshotStrategy;
import com.nona.changeTracking.spi.SnapshotStrategy;
import com.nona.changeTracking.spi.TrackingCapabilityProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DefaultTrackingCapabilityProvider 测试")
class DefaultTrackingCapabilityProviderTest {

    private TrackingCapabilityProvider provider;

    @BeforeEach
    void setUp() {
        provider = new DefaultTrackingCapabilityProvider();
    }

    @Test
    @DisplayName("getName() 应返回一个固定的、唯一的名称")
    void getName_shouldReturnFixedUniqueName() {
        assertEquals("default-reflection", provider.getName());
    }

    @Test
    @DisplayName("create() 应创建一个包含 ValueNode 策略的 TrackingCapability")
    void create_shouldCreateCapabilityWithDefaultStrategies() {
        final TrackingCapability<?> capability = provider.create();

        assertNotNull(capability);

        // 验证快照策略
        final SnapshotStrategy snapshotStrategy = capability.getSnapshotStrategy();
        assertInstanceOf(ValueNodeSnapshotStrategy.class, snapshotStrategy);

        // 验证比较策略
        final ComparisonStrategy<?> comparisonStrategy = capability.getComparisonStrategy();
        assertInstanceOf(ValueNodeComparisonStrategy.class, comparisonStrategy);
    }

    @Nested
    @DisplayName("链式配置方法测试")
    class ChainConfigTests {

        @Test
        @DisplayName("withIdentifier 应支持链式调用")
        void withIdentifier_shouldSupportChaining() {
            final TrackingCapabilityProvider result = provider
                    .withIdentifier(Order.class, Order::getId);

            assertSame(provider, result);
        }

        @Test
        @DisplayName("withValueType 应支持链式调用")
        void withValueType_shouldSupportChaining() {
            final TrackingCapabilityProvider result = provider
                    .withValueType(Money.class);

            assertSame(provider, result);
        }

        @Test
        @DisplayName("withValuePackage 应支持链式调用")
        void withValuePackage_shouldSupportChaining() {
            final TrackingCapabilityProvider result = provider
                    .withValuePackage("com.example.vo");

            assertSame(provider, result);
        }

        @Test
        @DisplayName("多个配置方法可以链式调用")
        void multipleConfigs_shouldChainTogether() {
            final TrackingCapabilityProvider result = provider
                    .withIdentifier(Order.class, Order::getId)
                    .withIdentifier(LineItem.class, LineItem::getId)
                    .withValueType(Money.class)
                    .withValuePackage("com.example.vo");

            assertSame(provider, result);
            assertNotNull(result.create());
        }
    }

    @Nested
    @DisplayName("配置传递到策略验证")
    class ConfigPropagationTests {

        @Test
        @DisplayName("withIdentifier 配置应传递到快照策略并在集合比较中生效")
        void withIdentifier_shouldAffectCollectionItemMatching() {
            // 配置 LineItem 的标识符提取器
            provider.withIdentifier(LineItem.class, LineItem::getId);
            final TrackingCapability<?> capability = provider.create();
            final UnitOfWork uow = new UnitOfWork(capability);

            // 创建订单并添加行项目
            final Order order = new Order(1L);
            order.addItem(new LineItem(100L, "商品A", 2));
            order.addItem(new LineItem(200L, "商品B", 1));
            uow.registerClean(order);

            // 修改已有项目的属性
            order.getItems().get(0).setQuantity(5);
            // 添加新项目
            order.addItem(new LineItem(300L, "商品C", 3));

            final var changeSet = uow.calculateChanges();

            assertFalse(changeSet.isEmpty());
            // 应该检测到属性修改和新增项
            assertTrue(changeSet.getLeafChanges().stream()
                    .anyMatch(c -> c instanceof FieldChange));
            assertTrue(changeSet.getLeafChanges().stream()
                    .anyMatch(c -> c instanceof ItemAddedChange));
        }

        @Test
        @DisplayName("withValueType 配置应传递到快照策略")
        void withValueType_shouldAffectSnapshotBehavior() {
            // 配置 Money 为值类型
            provider.withValueType(Money.class);
            final TrackingCapability<?> capability = provider.create();
            final UnitOfWork uow = new UnitOfWork(capability);

            final Product product = new Product(1L, "测试商品", new Money(new BigDecimal("100.00"), "CNY"));
            uow.registerClean(product);

            // 修改 Money 对象（整体替换）
            product.setPrice(new Money(new BigDecimal("150.00"), "CNY"));

            final var changeSet = uow.calculateChanges();

            // Money 作为值类型，应该产生字段变更而非展开内部字段
            assertFalse(changeSet.isEmpty());
            final var leafChanges = changeSet.getLeafChanges();
            assertTrue(leafChanges.stream()
                    .anyMatch(c -> c instanceof FieldChange && c.path().equals("price")));
        }
    }

    // ==================== 测试用的领域模型 ====================

    static class Order {
        private final Long id;
        private final List<LineItem> items = new ArrayList<>();

        Order(Long id) {
            this.id = id;
        }

        Long getId() { return id; }
        List<LineItem> getItems() { return items; }
        void addItem(LineItem item) { items.add(item); }
    }

    static class LineItem {
        private final Long id;
        private final String productName;
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

    static class Money {
        private final BigDecimal amount;
        private final String currency;

        Money(BigDecimal amount, String currency) {
            this.amount = amount;
            this.currency = currency;
        }

        BigDecimal getAmount() { return amount; }
        String getCurrency() { return currency; }
    }

    static class Product {
        private final Long id;
        private final String name;
        private Money price;

        Product(Long id, String name, Money price) {
            this.id = id;
            this.name = name;
            this.price = price;
        }

        Long getId() { return id; }
        String getName() { return name; }
        Money getPrice() { return price; }
        void setPrice(Money price) { this.price = price; }
    }
}
