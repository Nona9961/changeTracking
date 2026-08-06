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

        static class ParentWithShadowedField {
            String name = "parent";
        }

        static class ChildWithShadowedField extends ParentWithShadowedField {
            String name = "child";
        }

        @Test
        @DisplayName("应能正确快照嵌套对象")
        void shouldCorrectlySnapshotNestedObjects() {
            final User user = new User();
            final ValueNodeSnapshot snapshot = strategy.createSnapshot(user);

            assertInstanceOf(ObjectNode.class, snapshot.getSnapshotData());
            final ObjectNode userNode = (ObjectNode) snapshot.getSnapshotData();

            // 验证顶层字段
            assertEquals(new PrimitiveNode("Alice"), userNode.field("name"));

            // 验证嵌套对象
            assertInstanceOf(ObjectNode.class, userNode.field("address"));
            final ObjectNode addressNode = (ObjectNode) userNode.field("address");
            assertEquals(new PrimitiveNode("123 Main St"), addressNode.field("street"));
        }

        @Test
        @DisplayName("应能正确快照集合")
        void shouldCorrectlySnapshotCollections() {
            final User user = new User();
            final ValueNodeSnapshot snapshot = strategy.createSnapshot(user);
            final ObjectNode userNode = (ObjectNode) snapshot.getSnapshotData();

            // 验证集合
            assertInstanceOf(CollectionNode.class, userNode.field("tags"));
            final CollectionNode tagsNode = (CollectionNode) userNode.field("tags");
            assertEquals(2, tagsNode.size());
            final List<ValueNode> tags = new ArrayList<>();
            tagsNode.forEachItem(tags::add);
            assertEquals(List.of(new PrimitiveNode("vip"), new PrimitiveNode("local")), tags);
        }

        @Test
        @DisplayName("应能正确处理对象字段为 null 的情况")
        void shouldHandleNullFieldsInObject() {
            final User user = new User();
            user.name = null;
            final ValueNodeSnapshot snapshot = strategy.createSnapshot(user);
            final ObjectNode userNode = (ObjectNode) snapshot.getSnapshotData();

            assertEquals(new NullNode(), userNode.field("name"));
        }

        @Test
        @DisplayName("字段隐藏：子类同名字段应覆盖父类字段且不抛 Duplicate key")
        void fieldShadowing_shouldPreferSubclassField() {
            final ChildWithShadowedField entity = new ChildWithShadowedField();

            final ValueNodeSnapshot snapshot = assertDoesNotThrow(() -> strategy.createSnapshot(entity));
            assertInstanceOf(ObjectNode.class, snapshot.getSnapshotData());

            final ObjectNode node = (ObjectNode) snapshot.getSnapshotData();
            assertEquals(new PrimitiveNode("child"), node.field("name"));
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
            final ObjectNode childNode = (ObjectNode) parentNode.field("child");
            final ObjectNode backRefParentNode = (ObjectNode) childNode.field("parent");

            // 关键断言：对父对象的反向引用应该是同一个快照节点实例
            assertSame(parentNode, backRefParentNode, "The back-reference to parent should be the same node instance.");
        }

        @Test
        @DisplayName("应能处理自引用 Collection（list.add(list)）而不会导致 StackOverflowError")
        void shouldHandleSelfReferentialCollectionWithoutStackOverflow() {
            final List<Object> list = new ArrayList<>();
            list.add(list);

            final ValueNodeSnapshot snapshot = assertDoesNotThrow(() -> strategy.createSnapshot(list));

            assertInstanceOf(CollectionNode.class, snapshot.getSnapshotData());
            final CollectionNode listNode = (CollectionNode) snapshot.getSnapshotData();
            assertEquals(1, listNode.size());

            final ValueNode first = listNode.item(0);
            assertSame(listNode, first, "The self-reference should point to the same CollectionNode instance.");
        }

        @Test
        @DisplayName("应能处理自引用 Map（map.put(k,map)）而不会导致 StackOverflowError")
        void shouldHandleSelfReferentialMapWithoutStackOverflow() {
            final Map<String, Object> map = new HashMap<>();
            map.put("self", map);

            final ValueNodeSnapshot snapshot = assertDoesNotThrow(() -> strategy.createSnapshot(map));

            assertInstanceOf(CollectionNode.class, snapshot.getSnapshotData());
            final CollectionNode mapNode = (CollectionNode) snapshot.getSnapshotData();
            assertEquals(1, mapNode.size());

            final ValueNode entryNode = mapNode.item(0);
            assertInstanceOf(ObjectNode.class, entryNode);
            final ObjectNode mapEntryNode = (ObjectNode) entryNode;
            assertSame(mapNode, mapEntryNode.field("value"), "The self-reference should point to the same CollectionNode instance.");
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
            assertEquals(2, collectionNode.size());
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

            assertInstanceOf(CollectionNode.class, entityNode.field("attributes"));
            final CollectionNode mapNode = (CollectionNode) entityNode.field("attributes");
            assertEquals(2, mapNode.size());
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

        @Test
        @DisplayName("接口继承链：为父接口配置提取器，实现类现状漏检（特征：不递归父接口）")
        void interfaceInheritanceChain_shouldMissExtractorInCurrentImplementation() {
            // interface ExtendedIdentifiable extends BaseIdentifiable；class ExtendedProduct implements ExtendedIdentifiable。
            // 现状 findExtractor 只查直接接口（不递归父接口）→ 漏检 → identityHashCode 回退
            final Map<Class<?>, Function<Object, Object>> extractors = new HashMap<>();
            extractors.put(BaseIdentifiable.class, obj -> ((BaseIdentifiable) obj).getId());

            final TrackingConfiguration config = new TrackingConfiguration(
                    extractors,
                    Collections.emptySet(),
                    Collections.emptySet()
            );
            final ValueNodeSnapshotStrategy customStrategy = new ValueNodeSnapshotStrategy(config);

            final ExtendedProduct product = new ExtendedProduct(123L, "测试商品");
            final ObjectNode result = (ObjectNode) customStrategy.createSnapshot(product).getSnapshotData();

            assertInstanceOf(Integer.class, result.identifier(), "特征：接口继承链漏检回退 identityHashCode");
            assertNotEquals(123L, result.identifier(), "特征：业务 ID 未被提取");
        }

        @Test
        @DisplayName("父类实现接口链：为接口配置提取器，子类现状漏检（特征：父类链不检查其实现的接口）")
        void interfaceOnSuperclass_shouldMissExtractorInCurrentImplementation() {
            // class BaseSoftDeletableEntity implements Deletable；class SoftDeletableEntity extends BaseSoftDeletableEntity。
            // 现状父类链不检查父类实现的接口 → 漏检 → identityHashCode 回退
            final Map<Class<?>, Function<Object, Object>> extractors = new HashMap<>();
            extractors.put(Deletable.class, obj -> ((Deletable) obj).getDeleteKey());

            final TrackingConfiguration config = new TrackingConfiguration(
                    extractors,
                    Collections.emptySet(),
                    Collections.emptySet()
            );
            final ValueNodeSnapshotStrategy customStrategy = new ValueNodeSnapshotStrategy(config);

            final SoftDeletableEntity entity = new SoftDeletableEntity("DEL-1");
            final ObjectNode result = (ObjectNode) customStrategy.createSnapshot(entity).getSnapshotData();

            assertInstanceOf(Integer.class, result.identifier(), "特征：父类实现接口漏检回退 identityHashCode");
            assertNotEquals("DEL-1", result.identifier(), "特征：业务标识未被提取");
        }

        @Test
        @DisplayName("非集合项 identifier 现状为 identityHashCode（特征：冗余调用后回退，Javadoc 声称应为 null）")
        void nonCollectionComplexObject_identifier_shouldBeIdentityHashCode() {
            final Order order = new Order(1L, "ORD-001");
            final ObjectNode result = (ObjectNode) strategy.createSnapshot(order).getSnapshotData();

            final Integer expected = System.identityHashCode(order);
            assertEquals(expected, result.identifier(), "特征：非集合项也执行 extractIdentifier 并回退 identityHashCode");
        }
    }

    interface BaseIdentifiable {
        Long getId();
    }

    interface ExtendedIdentifiable extends BaseIdentifiable {
    }

    static class ExtendedProduct implements ExtendedIdentifiable {
        Long id;
        String name;

        ExtendedProduct(Long id, String name) {
            this.id = id;
            this.name = name;
        }

        @Override
        public Long getId() {
            return id;
        }
    }

    interface Deletable {
        String getDeleteKey();
    }

    static class BaseSoftDeletableEntity implements Deletable {
        String deleteKey;

        BaseSoftDeletableEntity(String deleteKey) {
            this.deleteKey = deleteKey;
        }

        @Override
        public String getDeleteKey() {
            return deleteKey;
        }
    }

    static class SoftDeletableEntity extends BaseSoftDeletableEntity {
        SoftDeletableEntity(String deleteKey) {
            super(deleteKey);
        }
    }

    @Nested
    @DisplayName("数组快照特征测试（现状：数组落入 processComplexObject，内容静默丢失为空 ObjectNode）")
    class ArraySnapshotTests {

        static class EntityWithByteArray {
            byte[] data = new byte[]{1, 2, 3};
        }

        @Test
        @DisplayName("byte[] 现状快照为空 ObjectNode（内容丢失）")
        void byteArray_shouldBeSnapshottedAsEmptyObjectNode() {
            final byte[] data = new byte[]{1, 2, 3};
            final ValueNode result = strategy.createSnapshot(data).getSnapshotData();

            assertInstanceOf(ObjectNode.class, result);
            assertTrue(isEmptyObjectNode((ObjectNode) result), "特征：数组内容静默丢失");
        }

        @Test
        @DisplayName("String[] 现状快照为空 ObjectNode（内容丢失）")
        void stringArray_shouldBeSnapshottedAsEmptyObjectNode() {
            final String[] data = {"a", "b", "c"};
            final ValueNode result = strategy.createSnapshot(data).getSnapshotData();

            assertInstanceOf(ObjectNode.class, result);
            assertTrue(isEmptyObjectNode((ObjectNode) result), "特征：数组内容静默丢失");
        }

        @Test
        @DisplayName("int[] 现状快照为空 ObjectNode（内容丢失）")
        void intArray_shouldBeSnapshottedAsEmptyObjectNode() {
            final int[] data = {1, 2, 3};
            final ValueNode result = strategy.createSnapshot(data).getSnapshotData();

            assertInstanceOf(ObjectNode.class, result);
            assertTrue(isEmptyObjectNode((ObjectNode) result), "特征：数组内容静默丢失");
        }

        @Test
        @DisplayName("Long[] 现状快照为空 ObjectNode（内容丢失）")
        void longArray_shouldBeSnapshottedAsEmptyObjectNode() {
            final Long[] data = {1L, 2L, 3L};
            final ValueNode result = strategy.createSnapshot(data).getSnapshotData();

            assertInstanceOf(ObjectNode.class, result);
            assertTrue(isEmptyObjectNode((ObjectNode) result), "特征：数组内容静默丢失");
        }

        @Test
        @DisplayName("对象内数组字段现状快照为空 ObjectNode（内容丢失）")
        void arrayField_insideObject_shouldBeSnapshottedAsEmptyObjectNode() {
            final EntityWithByteArray entity = new EntityWithByteArray();
            final ObjectNode node = (ObjectNode) strategy.createSnapshot(entity).getSnapshotData();

            assertInstanceOf(ObjectNode.class, node.field("data"));
            assertTrue(isEmptyObjectNode((ObjectNode) node.field("data")), "特征：数组内容静默丢失");
        }
    }

    @Nested
    @DisplayName("transient 字段处理特征测试（现状：仅过滤 static 不过滤 transient）")
    class TransientFieldTests {

        static class EntityWithTransient {
            String name = "persisted";
            transient String cache = "transient-value";
        }

        static class EntityWithStaticField {
            String instanceField = "instance";
            static String STATIC_FIELD = "static";
        }

        @Test
        @DisplayName("transient 字段现状会被快照（特征：不过滤 transient）")
        void transientField_shouldBeSnapshottedInCurrentImplementation() {
            final EntityWithTransient entity = new EntityWithTransient();
            final ObjectNode node = (ObjectNode) strategy.createSnapshot(entity).getSnapshotData();

            assertEquals(new PrimitiveNode("persisted"), node.field("name"));
            assertEquals(new PrimitiveNode("transient-value"), node.field("cache"), "特征：transient 字段被快照");
        }

        @Test
        @DisplayName("static 字段现状不会被快照（对照：过滤 static）")
        void staticField_shouldNotBeSnapshotted() {
            final ObjectNode node = (ObjectNode) strategy.createSnapshot(new EntityWithStaticField()).getSnapshotData();

            assertNull(node.field("STATIC_FIELD"), "static 字段应被过滤");
            assertEquals(new PrimitiveNode("instance"), node.field("instanceField"));
        }
    }

    @Nested
    @DisplayName("快照节点不可变契约测试（D11：final class + 只读 API，外部写 = 编译级不可能）")
    class NodeImmutabilityTests {

        @Test
        @DisplayName("ObjectNode 只暴露只读 API：field() 按名取值、forEachField 只读遍历、identifier()")
        void objectNode_shouldExposeReadOnlyApi() {
            final Map<String, ValueNode> fields = new HashMap<>();
            fields.put("name", new PrimitiveNode("Alice"));
            fields.put("age", new PrimitiveNode(30));
            final ObjectNode node = new ObjectNode(fields, 42L);

            // 按名取值
            assertEquals(new PrimitiveNode("Alice"), node.field("name"));
            assertEquals(new PrimitiveNode(30), node.field("age"));
            assertNull(node.field("missing"), "缺失字段应返回 null");

            // 只读遍历应覆盖全部字段
            final Map<String, ValueNode> visited = new HashMap<>();
            node.forEachField(visited::put);
            assertEquals(fields, visited, "forEachField 应遍历全部字段");

            assertEquals(42L, node.identifier());
        }

        @Test
        @DisplayName("CollectionNode 只暴露只读 API：size()、item(int)、forEachItem()")
        void collectionNode_shouldExposeReadOnlyApi() {
            final List<ValueNode> items = new ArrayList<>();
            items.add(new PrimitiveNode("a"));
            items.add(new PrimitiveNode("b"));
            final CollectionNode node = new CollectionNode(items);

            assertEquals(2, node.size());
            assertEquals(new PrimitiveNode("a"), node.item(0));
            assertEquals(new PrimitiveNode("b"), node.item(1));
            assertThrows(IndexOutOfBoundsException.class, () -> node.item(2), "越界访问应抛异常");

            // 只读遍历应覆盖全部项且保持顺序
            final List<ValueNode> visited = new ArrayList<>();
            node.forEachItem(visited::add);
            assertEquals(items, visited, "forEachItem 应遍历全部项");
        }

        @Test
        @DisplayName("ObjectNode equals 为内容语义：不同实例、相同内容相等，hashCode 一致")
        void objectNode_equals_shouldBeContentBased() {
            final ObjectNode a = new ObjectNode(Map.of("name", new PrimitiveNode("Alice")), 1L);
            final ObjectNode b = new ObjectNode(Map.of("name", new PrimitiveNode("Alice")), 1L);

            assertEquals(a, b, "相同内容的不同实例应相等");
            assertEquals(a.hashCode(), b.hashCode(), "相等对象 hashCode 必须一致");
        }

        @Test
        @DisplayName("ObjectNode equals 应区分字段内容与标识符")
        void objectNode_equals_shouldDistinguishFieldsAndIdentifier() {
            final ObjectNode base = new ObjectNode(Map.of("name", new PrimitiveNode("Alice")), 1L);

            assertNotEquals(base, new ObjectNode(Map.of("name", new PrimitiveNode("Bob")), 1L), "字段值不同应不相等");
            assertNotEquals(base, new ObjectNode(Map.of("name", new PrimitiveNode("Alice")), 2L), "标识符不同应不相等");
            assertNotEquals(base, new ObjectNode(Map.of("name", new PrimitiveNode("Alice"), "extra", new NullNode()), 1L), "字段集合不同应不相等");
        }

        @Test
        @DisplayName("CollectionNode equals 为内容语义（含顺序）")
        void collectionNode_equals_shouldBeContentBased() {
            final CollectionNode a = new CollectionNode(List.of(new PrimitiveNode("a"), new PrimitiveNode("b")));
            final CollectionNode b = new CollectionNode(List.of(new PrimitiveNode("a"), new PrimitiveNode("b")));

            assertEquals(a, b, "相同内容的不同实例应相等");
            assertEquals(a.hashCode(), b.hashCode(), "相等对象 hashCode 必须一致");
            assertNotEquals(a, new CollectionNode(List.of(new PrimitiveNode("a"))), "项数不同应不相等");
            assertNotEquals(a, new CollectionNode(List.of(new PrimitiveNode("b"), new PrimitiveNode("a"))), "顺序不同应不相等");
        }

        @Test
        @DisplayName("ObjectNode 与 CollectionNode 交叉循环引用 equals/hashCode/toString 不应 StackOverflow")
        void cyclicGraph_equalsHashCodeToString_shouldNotStackOverflow() {
            final Map<String, ValueNode> aFields = new HashMap<>();
            final ObjectNode a = new ObjectNode(aFields);
            final List<ValueNode> items = new ArrayList<>();
            final CollectionNode c = new CollectionNode(items);
            aFields.put("collection", c);
            items.add(a);

            assertDoesNotThrow(() -> a.equals(a), "自反 equals 不应栈溢出");
            assertDoesNotThrow(() -> a.hashCode(), "循环图 hashCode 不应栈溢出");
            assertDoesNotThrow(() -> a.toString(), "循环图 toString 不应栈溢出");
            assertDoesNotThrow(() -> c.hashCode(), "反向循环 hashCode 不应栈溢出");
            assertDoesNotThrow(() -> c.toString(), "反向循环 toString 不应栈溢出");
        }
    }

    /**
     * 判断 ObjectNode 是否不含任何字段（只读 API 下用于替代 fields().isEmpty()）。
     *
     * @param node 待判断的 ObjectNode。
     * @return 不含任何字段时返回 true。
     */
    private static boolean isEmptyObjectNode(final ObjectNode node) {
        final boolean[] empty = {true};
        node.forEachField((ignoredKey, ignoredValue) -> empty[0] = false);
        return empty[0];
    }
}
