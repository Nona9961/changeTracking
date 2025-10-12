package com.nona.changeTracking.internal.snapshot;

import com.nona.changeTracking.domain.model.unitofwork.MapSnapshot;
import com.nona.changeTracking.spi.SnapshotCreationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ReflectionMapSnapshotStrategy 综合测试")
@SuppressWarnings("unchecked")
class ReflectionMapSnapshotStrategyTest {

    private ReflectionMapSnapshotStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = ReflectionMapSnapshotStrategy.INSTANCE;
    }

    // --- Test Data Classes ---

    static class SimpleObject {
        private final int id = 1;
        private final String name = "simple";
    }

    static class Parent {
        private final String parentField = "parent value";
    }

    static class Child extends Parent {
        private final String childField = "child value";
    }

    static class JdkTypesObject {
        private final LocalDate localDate = LocalDate.of(2023, 1, 1);
        private final LocalDateTime localDateTime = LocalDateTime.of(2023, 1, 1, 10, 30);
        private final UUID uuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    }

    static class NestedObject {
        private final String description = "root";
        private final SimpleObject simple = new SimpleObject();
    }

    static class CollectionObject {
        private final List<String> stringList = List.of("A", "B", "C");
        private final Set<SimpleObject> objectSet = Set.of(new SimpleObject());
    }

    static class MapObject {
        private final Map<String, Integer> simpleMap = Map.of("one", 1, "two", 2);
        private final Map<String, SimpleObject> complexMap = Map.of("key", new SimpleObject());
    }

    static class Node {
        String name;
        Node next;
        Node(String name) { this.name = name; }
    }

    static class SelfReferencingObject {
        private final String name = "self";
        private SelfReferencingObject self;
        SelfReferencingObject() { this.self = this; }
    }


    @Test
    @DisplayName("当输入为 null 时应抛出 NullPointerException")
    void shouldThrowNullPointerExceptionForNullInput() {
        assertThrows(NullPointerException.class, () -> strategy.createSnapshot(null));
    }

    @Test
    @DisplayName("当根对象是简单类型时不应快照，而是抛出异常")
    void shouldThrowExceptionForSimpleTypeRoot() {
        assertThrows(SnapshotCreationException.class, () -> strategy.createSnapshot("a simple string"));
        assertThrows(SnapshotCreationException.class, () -> strategy.createSnapshot(123));
    }

    @Test
    @DisplayName("应能正确快照简单对象")
    void shouldSnapshotSimpleObject() {
        final MapSnapshot snapshot = (MapSnapshot) strategy.createSnapshot(new SimpleObject());
        final Map<String, Object> data = snapshot.getSnapshotData();

        assertAll("简单对象快照",
                () -> assertEquals(2, data.size()),
                () -> assertEquals(1, data.get("id")),
                () -> assertEquals("simple", data.get("name"))
        );
    }

    @Test
    @DisplayName("应能正确快照包含 JDK 8+ 时间和 UUID 类型的对象")
    void shouldSnapshotJdkTypesObject() {
        final JdkTypesObject original = new JdkTypesObject();
        final MapSnapshot snapshot = (MapSnapshot) strategy.createSnapshot(original);
        final Map<String, Object> data = snapshot.getSnapshotData();

        assertAll("JDK高级类型快照",
                () -> assertEquals(3, data.size()),
                () -> assertEquals(original.localDate, data.get("localDate")),
                () -> assertEquals(original.localDateTime, data.get("localDateTime")),
                () -> assertEquals(original.uuid, data.get("uuid"))
        );
    }

    @Test
    @DisplayName("应能正确快照继承的字段")
    void shouldSnapshotInheritedFields() {
        final MapSnapshot snapshot = (MapSnapshot) strategy.createSnapshot(new Child());
        final Map<String, Object> data = snapshot.getSnapshotData();

        assertAll("继承字段快照",
                () -> assertEquals(2, data.size()),
                () -> assertEquals("parent value", data.get("parentField")),
                () -> assertEquals("child value", data.get("childField"))
        );
    }

    @Test
    @DisplayName("应能正确快照嵌套对象")
    void shouldSnapshotNestedObject() {
        final MapSnapshot snapshot = (MapSnapshot) strategy.createSnapshot(new NestedObject());
        final Map<String, Object> data = snapshot.getSnapshotData();

        assertEquals("root", data.get("description"));
        final Map<String, Object> nestedData = (Map<String, Object>) data.get("simple");
        assertAll("嵌套对象快照",
                () -> assertNotNull(nestedData),
                () -> assertEquals(1, nestedData.get("id")),
                () -> assertEquals("simple", nestedData.get("name"))
        );
    }

    @Test
    @DisplayName("应能正确快照包含集合的字段")
    void shouldSnapshotObjectWithCollections() {
        final MapSnapshot snapshot = (MapSnapshot) strategy.createSnapshot(new CollectionObject());
        final Map<String, Object> data = snapshot.getSnapshotData();

        // 验证 List
        final List<String> stringList = (List<String>) data.get("stringList");
        assertEquals(List.of("A", "B", "C"), stringList);

        // 验证 Set 被转换为了 Collection/List
        // 我们断言它是一个 Collection，因为这是最通用的契约。
        // 生产代码实现为 ArrayList，但测试不应依赖具体实现。
        final Collection<Map<String, Object>> objectCollection = (Collection<Map<String, Object>>) data.get("objectSet");
        assertEquals(1, objectCollection.size());
        final Map<String, Object> element = objectCollection.iterator().next();
        assertEquals(1, element.get("id"));
    }


    @Test
    @DisplayName("应能正确快照包含Map的字段")
    void shouldSnapshotObjectWithMaps() {
        final MapSnapshot snapshot = (MapSnapshot) strategy.createSnapshot(new MapObject());
        final Map<String, Object> data = snapshot.getSnapshotData();

        final Map<String, Integer> simpleMap = (Map<String, Integer>) data.get("simpleMap");
        assertEquals(Map.of("one", 1, "two", 2), simpleMap);

        final Map<String, Map<String, Object>> complexMap = (Map<String, Map<String, Object>>) data.get("complexMap");
        assertEquals(1, complexMap.get("key").get("id"));
    }

    @Test
    @DisplayName("应能优雅地处理相互循环引用")
    void shouldHandleMutualCircularReference() {
        final Node nodeA = new Node("A");
        final Node nodeB = new Node("B");
        nodeA.next = nodeB;
        nodeB.next = nodeA; // A -> B -> A

        final MapSnapshot snapshot = (MapSnapshot) strategy.createSnapshot(nodeA);
        final Map<String, Object> dataA = snapshot.getSnapshotData();
        final Map<String, Object> dataB = (Map<String, Object>) dataA.get("next");

        assertAll("循环引用验证",
                () -> assertEquals("A", dataA.get("name")),
                () -> assertNotNull(dataB, "Node B should be present"),
                () -> assertEquals("B", dataB.get("name")),
                () -> assertSame(dataA, dataB.get("next"), "Node B's next should be the exact same map instance as Node A's map")
        );
    }

    @Test
    @DisplayName("应能优雅地处理自引用")
    void shouldHandleSelfReference() {
        final SelfReferencingObject obj = new SelfReferencingObject();
        final MapSnapshot snapshot = (MapSnapshot) strategy.createSnapshot(obj);
        final Map<String, Object> data = snapshot.getSnapshotData();

        assertAll("自引用验证",
                () -> assertEquals("self", data.get("name")),
                () -> assertSame(data, data.get("self"), "The 'self' field should point to the map representation of the object itself")
        );
    }

    @Test
    @DisplayName("应能正确处理 null 字段")
    void shouldHandleNullFields() {
        final Node nodeWithNullNext = new Node("A");
        nodeWithNullNext.next = null;

        final MapSnapshot snapshot = (MapSnapshot) strategy.createSnapshot(nodeWithNullNext);
        final Map<String, Object> data = snapshot.getSnapshotData();

        assertEquals("A", data.get("name"));
        assertTrue(data.containsKey("next"), "The key 'next' should exist");
        assertNull(data.get("next"), "The value for 'next' should be null");
    }
}
