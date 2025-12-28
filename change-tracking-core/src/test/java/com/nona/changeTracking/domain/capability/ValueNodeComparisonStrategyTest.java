package com.nona.changeTracking.domain.capability;

import com.nona.changeTracking.domain.model.changeset.*;
import com.nona.changeTracking.domain.model.snapshot.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ValueNodeComparisonStrategy 单元测试 (全覆盖)")
class ValueNodeComparisonStrategyTest {

    private ValueNodeComparisonStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new ValueNodeComparisonStrategy();
    }

    private ValueNodeSnapshot snapshotOf(ValueNode node) {
        return new ValueNodeSnapshot(node);
    }

    @Nested
    @DisplayName("简单字段与Null值测试")
    class PrimitiveAndNullTests {
        @Test
        @DisplayName("比较两个不同的 PrimitiveNode 树应返回 FieldChangeNode")
        void compare_differentPrimitiveTrees_shouldReturnFieldChange() {
            final ObjectNode oldTree = new ObjectNode(Map.of("name", new PrimitiveNode("Alice")));
            final ObjectNode newTree = new ObjectNode(Map.of("name", new PrimitiveNode("Bob")));
            final ChangeNode result = strategy.compare(snapshotOf(oldTree), snapshotOf(newTree));
            final List<ChangeNode> children = ((ContainerChangeNode) result).children();
            assertEquals(1, children.size());
            final FieldChangeNode change = (FieldChangeNode) children.get(0);
            assertEquals("name", change.path());
            assertEquals("Alice", change.oldValue());
            assertEquals("Bob", change.newValue());
        }

        @Test
        @DisplayName("字段从有值变为 null")
        void field_becomesNull_shouldBeReported() {
            final ObjectNode oldTree = new ObjectNode(Map.of("name", new PrimitiveNode("Alice")));
            final ObjectNode newTree = new ObjectNode(Map.of("name", new NullNode()));
            final ChangeNode result = strategy.compare(snapshotOf(oldTree), snapshotOf(newTree));
            final FieldChangeNode change = (FieldChangeNode) ((ContainerChangeNode) result).children().get(0);
            assertEquals("name", change.path());
            assertEquals("Alice", change.oldValue());
            assertNull(change.newValue());
        }

        @Test
        @DisplayName("字段从 null 变为有值")
        void field_becomesNonNull_shouldBeReported() {
            final ObjectNode oldTree = new ObjectNode(Map.of("name", new NullNode()));
            final ObjectNode newTree = new ObjectNode(Map.of("name", new PrimitiveNode("Alice")));
            final ChangeNode result = strategy.compare(snapshotOf(oldTree), snapshotOf(newTree));
            final FieldChangeNode change = (FieldChangeNode) ((ContainerChangeNode) result).children().get(0);
            assertEquals("name", change.path());
            assertNull(change.oldValue());
            assertEquals("Alice", change.newValue());
        }
    }

    @Nested
    @DisplayName("嵌套对象与结构变化测试")
    class NestedObjectTests {
        @Test
        @DisplayName("比较深层嵌套字段的变更应返回正确的层级结构")
        void compare_deeplyNestedFieldChange_shouldReturnCorrectHierarchy() {
            final ObjectNode oldAddress = new ObjectNode(Map.of("street", new PrimitiveNode("Main St")));
            final ObjectNode oldUser = new ObjectNode(Map.of("address", oldAddress));
            final ObjectNode newAddress = new ObjectNode(Map.of("street", new PrimitiveNode("Market St")));
            final ObjectNode newUser = new ObjectNode(Map.of("address", newAddress));
            final ChangeNode result = strategy.compare(snapshotOf(oldUser), snapshotOf(newUser));
            final ContainerChangeNode rootChange = (ContainerChangeNode) result;
            assertEquals(1, rootChange.children().size());
            final ContainerChangeNode addressChange = (ContainerChangeNode) rootChange.children().get(0);
            assertEquals("address", addressChange.path());
            assertEquals(1, addressChange.children().size());
            final FieldChangeNode streetChange = (FieldChangeNode) addressChange.children().get(0);
            assertEquals("address.street", streetChange.path());
        }

        @Test
        @DisplayName("对象新增一个字段")
        void field_addedToObject_shouldBeReported() {
            final ObjectNode oldTree = new ObjectNode(Map.of("name", new PrimitiveNode("Alice")));
            final ObjectNode newTree = new ObjectNode(Map.of("name", new PrimitiveNode("Alice"), "age", new PrimitiveNode(30)));
            final ChangeNode result = strategy.compare(snapshotOf(oldTree), snapshotOf(newTree));
            final FieldChangeNode change = (FieldChangeNode) ((ContainerChangeNode) result).children().get(0);
            assertEquals("age", change.path());
            assertNull(change.oldValue());
            assertEquals(30, change.newValue());
        }

        @Test
        @DisplayName("对象移除一个字段")
        void field_removedFromObject_shouldBeReported() {
            final ObjectNode oldTree = new ObjectNode(Map.of("name", new PrimitiveNode("Alice"), "age", new PrimitiveNode(30)));
            final ObjectNode newTree = new ObjectNode(Map.of("name", new PrimitiveNode("Alice")));
            final ChangeNode result = strategy.compare(snapshotOf(oldTree), snapshotOf(newTree));
            final FieldChangeNode change = (FieldChangeNode) ((ContainerChangeNode) result).children().get(0);
            assertEquals("age", change.path());
            assertEquals(30, change.oldValue());
            assertNull(change.newValue());
        }

        @Test
        @DisplayName("嵌套对象变为 null")
        void nestedObject_becomesNull_shouldBeReported() {
            final ObjectNode oldAddress = new ObjectNode(Map.of("street", new PrimitiveNode("Main St")));
            final ObjectNode oldUser = new ObjectNode(Map.of("address", oldAddress));
            final ObjectNode newUser = new ObjectNode(Map.of("address", new NullNode()));
            final ChangeNode result = strategy.compare(snapshotOf(oldUser), snapshotOf(newUser));
            final FieldChangeNode change = (FieldChangeNode) ((ContainerChangeNode) result).children().get(0);
            assertEquals("address", change.path());
            assertEquals(oldAddress, change.oldValue());
            assertNull(change.newValue());
        }
    }

    @Nested
    @DisplayName("集合复杂变更测试")
    class CollectionTests {
        private ObjectNode createItemNode(String id, String value) {
            // 使用 id 字符串作为业务标识符
            return new ObjectNode(Map.of("id", new PrimitiveNode(id), "value", new PrimitiveNode(value)), id);
        }

        @Test
        @DisplayName("当集合新增一个元素时，应返回正确的结构")
        void compare_itemAdded_shouldReturnCorrectStructure() {
            final CollectionNode oldList = new CollectionNode(List.of(createItemNode("A", "v1")));
            final CollectionNode newList = new CollectionNode(List.of(createItemNode("A", "v1"), createItemNode("B", "v2")));
            final ObjectNode oldRoot = new ObjectNode(Map.of("items", oldList));
            final ObjectNode newRoot = new ObjectNode(Map.of("items", newList));
            final ChangeNode result = strategy.compare(snapshotOf(oldRoot), snapshotOf(newRoot));
            // 集合变更直接出现在根节点下，不再有 items 包裹层
            final ItemAddedNode addedNode = (ItemAddedNode) ((ContainerChangeNode) result).children().get(0);
            assertEquals("items", addedNode.path());
        }

        @Test
        @DisplayName("当集合移除一个元素时，应返回正确的结构")
        void compare_itemRemoved_shouldReturnCorrectStructure() {
            final CollectionNode oldList = new CollectionNode(List.of(createItemNode("A", "v1"), createItemNode("B", "v2")));
            final CollectionNode newList = new CollectionNode(List.of(createItemNode("A", "v1")));
            final ObjectNode oldRoot = new ObjectNode(Map.of("items", oldList));
            final ObjectNode newRoot = new ObjectNode(Map.of("items", newList));
            final ChangeNode result = strategy.compare(snapshotOf(oldRoot), snapshotOf(newRoot));
            final ItemRemovedNode removedNode = (ItemRemovedNode) ((ContainerChangeNode) result).children().get(0);
            assertEquals("items", removedNode.path());
        }

        @Test
        @DisplayName("当集合中一个元素的字段更新时，应返回正确的层级结构")
        void compare_itemUpdated_shouldReturnCorrectHierarchy() {
            final CollectionNode oldList = new CollectionNode(List.of(createItemNode("A", "v1")));
            final CollectionNode newList = new CollectionNode(List.of(createItemNode("A", "v2")));
            final ObjectNode oldRoot = new ObjectNode(Map.of("items", oldList));
            final ObjectNode newRoot = new ObjectNode(Map.of("items", newList));
            final ChangeNode result = strategy.compare(snapshotOf(oldRoot), snapshotOf(newRoot));
            // items[A] 直接出现在根节点下
            final ContainerChangeNode itemAChange = (ContainerChangeNode) ((ContainerChangeNode) result).children().get(0);
            assertEquals("items[A]", itemAChange.path());
            assertEquals(1, itemAChange.children().size());
            final FieldChangeNode valueChange = (FieldChangeNode) itemAChange.children().get(0);
            assertEquals("items[A].value", valueChange.path());
        }

        @Test
        @DisplayName("集合同时发生增删改")
        void collection_withAddRemoveUpdate_shouldReportAllChanges() {
            final CollectionNode oldList = new CollectionNode(List.of(createItemNode("A", "v1"), createItemNode("B", "v2")));
            final CollectionNode newList = new CollectionNode(List.of(createItemNode("A", "v1-updated"), createItemNode("C", "v3")));
            final ObjectNode oldRoot = new ObjectNode(Map.of("items", oldList));
            final ObjectNode newRoot = new ObjectNode(Map.of("items", newList));
            final ChangeNode result = strategy.compare(snapshotOf(oldRoot), snapshotOf(newRoot));
            final List<ChangeNode> children = ((ContainerChangeNode) result).children();
            assertEquals(3, children.size());
            assertTrue(children.stream().anyMatch(c -> c instanceof ContainerChangeNode && c.path().equals("items[A]")));
            assertTrue(children.stream().anyMatch(c -> c instanceof ItemRemovedNode && c.path().equals("items")));
            assertTrue(children.stream().anyMatch(c -> c instanceof ItemAddedNode && c.path().equals("items")));
        }
    }
}
