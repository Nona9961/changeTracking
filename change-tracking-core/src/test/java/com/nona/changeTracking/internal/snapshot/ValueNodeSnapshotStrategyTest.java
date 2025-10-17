package com.nona.changeTracking.internal.snapshot;

import com.nona.changeTracking.domain.model.snapshot.*;
import lombok.Data;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ValueNodeSnapshotStrategy 单元测试")
class ValueNodeSnapshotStrategyTest {

    private ValueNodeSnapshotStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new ValueNodeSnapshotStrategy();
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

        @Data
        static class Address {
            private String street = "123 Main St";
        }

        @Data
        static class User {
            private String name = "Alice";
            private Address address = new Address();
            private List<String> tags = List.of("vip", "local");
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
            user.setName(null);
            final ValueNodeSnapshot snapshot = strategy.createSnapshot(user);
            final ObjectNode userNode = (ObjectNode) snapshot.getSnapshotData();

            assertEquals(new NullNode(), userNode.fields().get("name"));
        }
    }

    @Nested
    @DisplayName("循环引用处理")
    class CircularReferenceTests {

        @Data
        static class Parent {
            String name = "P1";
            Child child;
        }

        @Data
        static class Child {
            String name = "C1";
            Parent parent;
        }

        @Test
        @DisplayName("应能处理循环引用而不会导致 StackOverflowError")
        void shouldHandleCircularReferencesWithoutStackOverflow() {
            final Parent parent = new Parent();
            final Child child = new Child();
            parent.setChild(child);
            child.setParent(parent);

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
}
