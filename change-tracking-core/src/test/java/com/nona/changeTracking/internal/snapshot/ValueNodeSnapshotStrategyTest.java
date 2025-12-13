package com.nona.changeTracking.internal.snapshot;

import com.nona.changeTracking.domain.capability.TrackingConfiguration;
import com.nona.changeTracking.domain.model.snapshot.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ValueNodeSnapshotStrategy 单元测试")
class ValueNodeSnapshotStrategyTest {

    private ValueNodeSnapshotStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new ValueNodeSnapshotStrategy(TrackingConfiguration.empty());
    }

    @Nested
    @DisplayName("基本和已知值类型处理")
    class PrimitiveAndKnownValueTests {

        @Test
        @DisplayName("应将基本类型和字符串快照为 PrimitiveNode")
        void shouldSnapshotPrimitivesAsStringNode() {
            assertEquals(new PrimitiveNode("hello"), strategy.createSnapshot("hello").getSnapshotData());
            assertEquals(new PrimitiveNode(123), strategy.createSnapshot(123).getSnapshotData());
            assertEquals(new PrimitiveNode(true), strategy.createSnapshot(true).getSnapshotData());
        }

        @Test
        @DisplayName("应将已知的JDK值类型（如LocalDateTime）快照为 PrimitiveNode")
        void shouldSnapshotKnownValueTypesAsPrimitiveNode() {
            final LocalDateTime now = LocalDateTime.now();
            final BigDecimal money = new BigDecimal("199.99");

            assertEquals(new PrimitiveNode(now), strategy.createSnapshot(now).getSnapshotData());
            assertEquals(new PrimitiveNode(money), strategy.createSnapshot(money).getSnapshotData());
        }

        @Test
        @DisplayName("应将 null 快照为 NullNode")
        void shouldSnapshotNullAsNullNode() {
            assertEquals(new NullNode(), strategy.createSnapshot(null).getSnapshotData());
        }
    }

    @Nested
    @DisplayName("复杂对象和集合处理")
    class ComplexObjectTests {

        static class Address {
            String street = "123 Main St";
        }

        static class User {
            String name = "Alice";
            Address address = new Address();
            List<String> tags = List.of("vip", "local");
        }

        @Test
        @DisplayName("应能正确快照嵌套对象")
        void shouldCorrectlySnapshotNestedObjects() {
            final User user = new User();
            final ValueNodeSnapshot snapshot = strategy.createSnapshot(user);

            assertInstanceOf(ObjectNode.class, snapshot.getSnapshotData());
            final ObjectNode userNode = (ObjectNode) snapshot.getSnapshotData();

            // 验证顶层字段
            assertEquals(new PrimitiveNode("Alice"), userNode.fields().get("name"));

            // 验证嵌套对象
            assertInstanceOf(ObjectNode.class, userNode.fields().get("address"));
            final ObjectNode addressNode = (ObjectNode) userNode.fields().get("address");
            assertEquals(new PrimitiveNode("123 Main St"), addressNode.fields().get("street"));
        }

        @Test
        @DisplayName("应能正确快照集合")
        void shouldCorrectlySnapshotCollections() {
            final User user = new User();
            final ValueNodeSnapshot snapshot = strategy.createSnapshot(user);
            final ObjectNode userNode = (ObjectNode) snapshot.getSnapshotData();

            // 验证集合
            assertInstanceOf(CollectionNode.class, userNode.fields().get("tags"));
            final CollectionNode tagsNode = (CollectionNode) userNode.fields().get("tags");
            assertEquals(2, tagsNode.items().size());
            assertTrue(tagsNode.items().contains(new PrimitiveNode("vip")));
            assertTrue(tagsNode.items().contains(new PrimitiveNode("local")));
        }

        @Test
        @DisplayName("应能正确处理对象字段为 null 的情况")
        void shouldHandleNullFieldsInObject() {
            final User user = new User();
            user.name = null;
            final ValueNodeSnapshot snapshot = strategy.createSnapshot(user);
            final ObjectNode userNode = (ObjectNode) snapshot.getSnapshotData();

            assertEquals(new NullNode(), userNode.fields().get("name"));
        }
    }

    @Nested
    @DisplayName("循环引用处理")
    class CircularReferenceTests {

        static class Parent {
            String name = "P1";
            Child child;
        }

        static class Child {
            String name = "C1";
            Parent parent;
        }

        @Test
        @DisplayName("应能处理循环引用而不会导致 StackOverflowError")
        void shouldHandleCircularReferencesWithoutStackOverflow() {
            final Parent parent = new Parent();
            final Child child = new Child();
            parent.child = child;
            child.parent = parent;

            // 如果不处理循环引用，这里会抛出 StackOverflowError
            final ValueNodeSnapshot snapshot = assertDoesNotThrow(() -> strategy.createSnapshot(parent));

            // 验证结构
            final ObjectNode parentNode = (ObjectNode) snapshot.getSnapshotData();
            final ObjectNode childNode = (ObjectNode) parentNode.fields().get("child");
            final ObjectNode backRefParentNode = (ObjectNode) childNode.fields().get("parent");

            // 关键断言：对父对象的反向引用应该是同一个快照节点实例
            assertSame(parentNode, backRefParentNode, "The back-reference to parent should be the same node instance.");
        }
    }

    @Nested
    @DisplayName("枚举和UUID类型处理")
    class EnumAndUuidTests {

        enum Status { ACTIVE, INACTIVE }

        @Test
        @DisplayName("应将枚举快照为 PrimitiveNode")
        void shouldSnapshotEnumAsPrimitiveNode() {
            final Status status = Status.ACTIVE;
            final ValueNode result = strategy.createSnapshot(status).getSnapshotData();

            assertInstanceOf(PrimitiveNode.class, result);
            assertEquals(Status.ACTIVE, ((PrimitiveNode) result).value());
        }

        @Test
        @DisplayName("应将 UUID 快照为 PrimitiveNode")
        void shouldSnapshotUuidAsPrimitiveNode() {
            final UUID uuid = UUID.randomUUID();
            final ValueNode result = strategy.createSnapshot(uuid).getSnapshotData();

            assertInstanceOf(PrimitiveNode.class, result);
            assertEquals(uuid, ((PrimitiveNode) result).value());
        }
    }

    @Nested
    @DisplayName("Map类型处理")
    class MapTests {

        @Test
        @DisplayName("应将 Map 快照为 CollectionNode")
        void shouldSnapshotMapAsCollectionNode() {
            final Map<String, Integer> map = new HashMap<>();
            map.put("a", 1);
            map.put("b", 2);
            final ValueNode result = strategy.createSnapshot(map).getSnapshotData();

            assertInstanceOf(CollectionNode.class, result);
            final CollectionNode collectionNode = (CollectionNode) result;
            assertEquals(2, collectionNode.items().size());
        }

        static class EntityWithMap {
            Map<String, String> attributes = new HashMap<>(Map.of("key1", "value1", "key2", "value2"));
        }

        @Test
        @DisplayName("应能正确快照包含 Map 的对象")
        void shouldCorrectlySnapshotObjectWithMap() {
            final EntityWithMap entity = new EntityWithMap();
            final ValueNodeSnapshot snapshot = strategy.createSnapshot(entity);
            final ObjectNode entityNode = (ObjectNode) snapshot.getSnapshotData();

            assertInstanceOf(CollectionNode.class, entityNode.fields().get("attributes"));
            final CollectionNode mapNode = (CollectionNode) entityNode.fields().get("attributes");
            assertEquals(2, mapNode.items().size());
        }
    }

    @Nested
    @DisplayName("自定义值类型配置")
    class CustomValueTypeTests {

        // 自定义值对象类（不在默认值类型包中）
        static class Money {
            private final BigDecimal amount;
            private final String currency;

            Money(BigDecimal amount, String currency) {
                this.amount = amount;
                this.currency = currency;
            }
        }

        @Test
        @DisplayName("未配置时，自定义类应展开为 ObjectNode")
        void withoutConfig_customClass_shouldBeObjectNode() {
            final Money money = new Money(new BigDecimal("100.00"), "CNY");
            final ValueNode result = strategy.createSnapshot(money).getSnapshotData();

            // 未配置时，Money 会被展开为 ObjectNode
            assertInstanceOf(ObjectNode.class, result);
        }

        @Test
        @DisplayName("配置 withValueType 后，自定义类应快照为 PrimitiveNode")
        void withValueTypeConfig_customClass_shouldBePrimitiveNode() {
            // 配置 Money 为值类型
            final TrackingConfiguration config = new TrackingConfiguration(
                    Collections.emptyMap(),
                    Set.of(Money.class),
                    Collections.emptySet()
            );
            final ValueNodeSnapshotStrategy customStrategy = new ValueNodeSnapshotStrategy(config);

            final Money money = new Money(new BigDecimal("100.00"), "CNY");
            final ValueNode result = customStrategy.createSnapshot(money).getSnapshotData();

            // 配置后，Money 被视为原始值
            assertInstanceOf(PrimitiveNode.class, result);
            assertEquals(money, ((PrimitiveNode) result).value());
        }

        @Test
        @DisplayName("配置 withValuePackage 后，该包下的类应快照为 PrimitiveNode")
        void withValuePackageConfig_classInPackage_shouldBePrimitiveNode() {
            // 配置整个包为值类型包
            final TrackingConfiguration config = new TrackingConfiguration(
                    Collections.emptyMap(),
                    Collections.emptySet(),
                    Set.of("com.nona.changeTracking.internal.snapshot")
            );
            final ValueNodeSnapshotStrategy customStrategy = new ValueNodeSnapshotStrategy(config);

            final Money money = new Money(new BigDecimal("100.00"), "CNY");
            final ValueNode result = customStrategy.createSnapshot(money).getSnapshotData();

            // 配置后，该包下的类被视为原始值
            assertInstanceOf(PrimitiveNode.class, result);
        }
    }

    @Nested
    @DisplayName("业务标识符提取")
    class IdentifierExtractionTests {

        static class Order {
            Long id;
            String orderNumber;

            Order(Long id, String orderNumber) {
                this.id = id;
                this.orderNumber = orderNumber;
            }

            Long getId() { return id; }
        }

        static class SpecialOrder extends Order {
            SpecialOrder(Long id, String orderNumber) {
                super(id, orderNumber);
            }
        }

        interface Identifiable {
            Long getId();
        }

        static class Product implements Identifiable {
            Long id;
            String name;

            Product(Long id, String name) {
                this.id = id;
                this.name = name;
            }

            @Override
            public Long getId() { return id; }
        }

        @Test
        @DisplayName("未配置提取器时，应使用 identityHashCode 作为标识符")
        void withoutExtractor_shouldUseIdentityHashCode() {
            final Order order = new Order(1L, "ORD-001");
            final ObjectNode result = (ObjectNode) strategy.createSnapshot(order).getSnapshotData();

            // 标识符应该是 Integer 类型（identityHashCode 的包装）
            assertNotNull(result.identifier());
            assertInstanceOf(Integer.class, result.identifier());
        }

        @Test
        @DisplayName("配置提取器后，应使用业务 ID 作为标识符")
        void withExtractor_shouldUseBusinessId() {
            // 配置 Order 的标识符提取器
            final Map<Class<?>, Function<Object, Object>> extractors = new HashMap<>();
            extractors.put(Order.class, obj -> ((Order) obj).getId());

            final TrackingConfiguration config = new TrackingConfiguration(
                    extractors,
                    Collections.emptySet(),
                    Collections.emptySet()
            );
            final ValueNodeSnapshotStrategy customStrategy = new ValueNodeSnapshotStrategy(config);

            final Order order = new Order(42L, "ORD-001");
            final ObjectNode result = (ObjectNode) customStrategy.createSnapshot(order).getSnapshotData();

            // 标识符应该是配置的业务 ID
            assertEquals(42L, result.identifier());
        }

        @Test
        @DisplayName("继承链查找：子类应能使用父类配置的提取器")
        void inheritanceChain_shouldFindExtractorFromParentClass() {
            // 只为父类 Order 配置提取器
            final Map<Class<?>, Function<Object, Object>> extractors = new HashMap<>();
            extractors.put(Order.class, obj -> ((Order) obj).getId());

            final TrackingConfiguration config = new TrackingConfiguration(
                    extractors,
                    Collections.emptySet(),
                    Collections.emptySet()
            );
            final ValueNodeSnapshotStrategy customStrategy = new ValueNodeSnapshotStrategy(config);

            // 使用子类 SpecialOrder
            final SpecialOrder specialOrder = new SpecialOrder(99L, "SPE-001");
            final ObjectNode result = (ObjectNode) customStrategy.createSnapshot(specialOrder).getSnapshotData();

            // 子类应继承父类的提取器
            assertEquals(99L, result.identifier());
        }

        @Test
        @DisplayName("接口查找：实现类应能使用接口配置的提取器")
        void interfaceLookup_shouldFindExtractorFromInterface() {
            // 为接口 Identifiable 配置提取器
            final Map<Class<?>, Function<Object, Object>> extractors = new HashMap<>();
            extractors.put(Identifiable.class, obj -> ((Identifiable) obj).getId());

            final TrackingConfiguration config = new TrackingConfiguration(
                    extractors,
                    Collections.emptySet(),
                    Collections.emptySet()
            );
            final ValueNodeSnapshotStrategy customStrategy = new ValueNodeSnapshotStrategy(config);

            // 使用实现了 Identifiable 接口的 Product
            final Product product = new Product(123L, "测试商品");
            final ObjectNode result = (ObjectNode) customStrategy.createSnapshot(product).getSnapshotData();

            // 应使用接口配置的提取器
            assertEquals(123L, result.identifier());
        }

        @Test
        @DisplayName("提取器返回 null 时，应回退到 identityHashCode")
        void extractorReturnsNull_shouldFallbackToIdentityHashCode() {
            // 配置一个返回 null 的提取器
            final Map<Class<?>, Function<Object, Object>> extractors = new HashMap<>();
            extractors.put(Order.class, obj -> null);

            final TrackingConfiguration config = new TrackingConfiguration(
                    extractors,
                    Collections.emptySet(),
                    Collections.emptySet()
            );
            final ValueNodeSnapshotStrategy customStrategy = new ValueNodeSnapshotStrategy(config);

            final Order order = new Order(1L, "ORD-001");
            final ObjectNode result = (ObjectNode) customStrategy.createSnapshot(order).getSnapshotData();

            // 提取器返回 null 时，应回退到 identityHashCode
            assertNotNull(result.identifier());
            assertInstanceOf(Integer.class, result.identifier());
        }
    }
}
