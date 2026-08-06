package com.nona.changeTracking.domain.capability;

import com.nona.changeTracking.domain.model.changeset.*;
import com.nona.changeTracking.domain.model.snapshot.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
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
        void compare_differentPrimitiveTrees_shouldReturnValueChange() {
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
        void compare_deeplyNestedValueChange_shouldReturnCorrectHierarchy() {
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
        @DisplayName("嵌套对象变为 null 应产出 ObjectFieldChangeNode（oldNode=原 ObjectNode、newNode=NullNode）")
        void nestedObject_becomesNull_shouldBeReported() {
            final ObjectNode oldAddress = new ObjectNode(Map.of("street", new PrimitiveNode("Main St")));
            final ObjectNode oldUser = new ObjectNode(Map.of("address", oldAddress));
            final ObjectNode newUser = new ObjectNode(Map.of("address", new NullNode()));
            final ChangeNode result = strategy.compare(snapshotOf(oldUser), snapshotOf(newUser));
            final ObjectFieldChangeNode change = (ObjectFieldChangeNode) ((ContainerChangeNode) result).children().get(0);
            assertEquals("address", change.path());
            assertSame(oldAddress, change.oldNode(), "oldNode 应为原始 ObjectNode 实例（快照表示）");
            assertTrue(change.newNode() instanceof NullNode, "newNode 应为 NullNode");
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
            // 集合变更被包裹在 ContainerChangeNode(path: "items") 中
            final ContainerChangeNode itemsChange = (ContainerChangeNode) ((ContainerChangeNode) result).children().get(0);
            assertEquals("items", itemsChange.path());
            final ItemAddedNode addedNode = (ItemAddedNode) itemsChange.children().get(0);
            assertEquals("items[B]", addedNode.path());
        }

        @Test
        @DisplayName("当集合移除一个元素时，应返回正确的结构")
        void compare_itemRemoved_shouldReturnCorrectStructure() {
            final CollectionNode oldList = new CollectionNode(List.of(createItemNode("A", "v1"), createItemNode("B", "v2")));
            final CollectionNode newList = new CollectionNode(List.of(createItemNode("A", "v1")));
            final ObjectNode oldRoot = new ObjectNode(Map.of("items", oldList));
            final ObjectNode newRoot = new ObjectNode(Map.of("items", newList));
            final ChangeNode result = strategy.compare(snapshotOf(oldRoot), snapshotOf(newRoot));
            // 集合变更被包裹在 ContainerChangeNode(path: "items") 中
            final ContainerChangeNode itemsChange = (ContainerChangeNode) ((ContainerChangeNode) result).children().get(0);
            assertEquals("items", itemsChange.path());
            final ItemRemovedNode removedNode = (ItemRemovedNode) itemsChange.children().get(0);
            assertEquals("items[B]", removedNode.path());
        }

        @Test
        @DisplayName("当集合中一个元素的字段更新时，应返回正确的层级结构")
        void compare_itemUpdated_shouldReturnCorrectHierarchy() {
            final CollectionNode oldList = new CollectionNode(List.of(createItemNode("A", "v1")));
            final CollectionNode newList = new CollectionNode(List.of(createItemNode("A", "v2")));
            final ObjectNode oldRoot = new ObjectNode(Map.of("items", oldList));
            final ObjectNode newRoot = new ObjectNode(Map.of("items", newList));
            final ChangeNode result = strategy.compare(snapshotOf(oldRoot), snapshotOf(newRoot));
            // 集合变更被包裹在 ContainerChangeNode(path: "items") 中
            final ContainerChangeNode itemsChange = (ContainerChangeNode) ((ContainerChangeNode) result).children().get(0);
            assertEquals("items", itemsChange.path());
            // items[A] 是 items 的子节点
            final ContainerChangeNode itemAChange = (ContainerChangeNode) itemsChange.children().get(0);
            assertEquals("items[A]", itemAChange.path());
            assertEquals(1, itemAChange.children().size());
            final FieldChangeNode valueChange = (FieldChangeNode) itemAChange.children().get(0);
            assertEquals("items[A].value", valueChange.path());
        }

        @Test
        @DisplayName("集合增删改混合时变更输出顺序 = 插入序（old 项顺序，新增项追加在后）")
        void collectionChanges_shouldFollowInsertionOrder() {
            final CollectionNode oldList = new CollectionNode(List.of(createItemNode("B", "v2"), createItemNode("A", "v1")));
            final CollectionNode newList = new CollectionNode(List.of(createItemNode("A", "v1-updated"), createItemNode("C", "v3")));
            final ObjectNode oldRoot = new ObjectNode(Map.of("items", oldList));
            final ObjectNode newRoot = new ObjectNode(Map.of("items", newList));

            final ChangeNode result = strategy.compare(snapshotOf(oldRoot), snapshotOf(newRoot));
            final ContainerChangeNode itemsChange = (ContainerChangeNode) ((ContainerChangeNode) result).children().get(0);
            assertEquals("items", itemsChange.path());

            // 插入序：old 集合项 B、A 在前，新增项 C 追加在后（非字典序 A、B、C）
            assertEquals(List.of("items[B]", "items[A]", "items[C]"),
                    itemsChange.children().stream().map(ChangeNode::path).toList());
        }

        @Test
        @DisplayName("集合同时发生增删改")
        void collection_withAddRemoveUpdate_shouldReportAllChanges() {
            final CollectionNode oldList = new CollectionNode(List.of(createItemNode("A", "v1"), createItemNode("B", "v2")));
            final CollectionNode newList = new CollectionNode(List.of(createItemNode("A", "v1-updated"), createItemNode("C", "v3")));
            final ObjectNode oldRoot = new ObjectNode(Map.of("items", oldList));
            final ObjectNode newRoot = new ObjectNode(Map.of("items", newList));
            final ChangeNode result = strategy.compare(snapshotOf(oldRoot), snapshotOf(newRoot));
            // 集合变更被包裹在 ContainerChangeNode(path: "items") 中
            final ContainerChangeNode itemsChange = (ContainerChangeNode) ((ContainerChangeNode) result).children().get(0);
            assertEquals("items", itemsChange.path());
            final List<ChangeNode> children = itemsChange.children();
            assertEquals(3, children.size());
            assertTrue(children.stream().anyMatch(c -> c instanceof ContainerChangeNode && c.path().equals("items[A]")));
            assertTrue(children.stream().anyMatch(c -> c instanceof ItemRemovedNode && c.path().equals("items[B]")));
            assertTrue(children.stream().anyMatch(c -> c instanceof ItemAddedNode && c.path().equals("items[C]")));
        }

        @Test
        @DisplayName("重复 Primitive 值不应因匹配丢项（应能识别多余项删除）")
        void duplicatePrimitiveValues_shouldNotDropChanges() {
            final CollectionNode oldList = new CollectionNode(List.of(new PrimitiveNode("A"), new PrimitiveNode("A")));
            final CollectionNode newList = new CollectionNode(List.of(new PrimitiveNode("A")));
            final ObjectNode oldRoot = new ObjectNode(Map.of("items", oldList));
            final ObjectNode newRoot = new ObjectNode(Map.of("items", newList));

            final ChangeNode result = strategy.compare(snapshotOf(oldRoot), snapshotOf(newRoot));
            final ContainerChangeNode itemsChange = (ContainerChangeNode) ((ContainerChangeNode) result).children().get(0);
            assertEquals("items", itemsChange.path());

            assertEquals(1, itemsChange.children().size());
            final ItemRemovedNode removedNode = (ItemRemovedNode) itemsChange.children().get(0);
            assertEquals("items[A#2]", removedNode.path());
        }

        @Test
        @DisplayName("重复 identifier 不应因匹配丢项（应能识别多余项删除）")
        void duplicateIdentifiers_shouldNotDropChanges() {
            final CollectionNode oldList = new CollectionNode(List.of(createItemNode("A", "v1"), createItemNode("A", "v2")));
            final CollectionNode newList = new CollectionNode(List.of(createItemNode("A", "v1")));
            final ObjectNode oldRoot = new ObjectNode(Map.of("items", oldList));
            final ObjectNode newRoot = new ObjectNode(Map.of("items", newList));

            final ChangeNode result = strategy.compare(snapshotOf(oldRoot), snapshotOf(newRoot));
            final ContainerChangeNode itemsChange = (ContainerChangeNode) ((ContainerChangeNode) result).children().get(0);
            assertEquals("items", itemsChange.path());

            assertEquals(1, itemsChange.children().size());
            final ItemRemovedNode removedNode = (ItemRemovedNode) itemsChange.children().get(0);
            assertEquals("items[A#2]", removedNode.path());
        }

        @Test
        @DisplayName("集合包含 null 元素时应能识别增删（不应静默忽略）")
        void nullElement_shouldBeReported() {
            final CollectionNode oldList = new CollectionNode(List.of(new NullNode()));
            final CollectionNode newList = new CollectionNode(List.of());
            final ObjectNode oldRoot = new ObjectNode(Map.of("items", oldList));
            final ObjectNode newRoot = new ObjectNode(Map.of("items", newList));

            final ChangeNode result = strategy.compare(snapshotOf(oldRoot), snapshotOf(newRoot));
            final ContainerChangeNode itemsChange = (ContainerChangeNode) ((ContainerChangeNode) result).children().get(0);
            assertEquals("items", itemsChange.path());

            assertEquals(1, itemsChange.children().size());
            final ItemRemovedNode removedNode = (ItemRemovedNode) itemsChange.children().get(0);
            assertEquals("items[null]", removedNode.path());
        }

        @Test
        @DisplayName("Map null key 的 entry 不应被忽略（应能识别 value 变更）")
        void mapNullKeyEntry_shouldNotBeIgnored() {
            final ObjectNode oldEntry = new ObjectNode(Map.of(
                    "key", new NullNode(),
                    "value", new PrimitiveNode("v1")
            ), null);
            final ObjectNode newEntry = new ObjectNode(Map.of(
                    "key", new NullNode(),
                    "value", new PrimitiveNode("v2")
            ), null);

            final ObjectNode oldRoot = new ObjectNode(Map.of("map", new CollectionNode(List.of(oldEntry))));
            final ObjectNode newRoot = new ObjectNode(Map.of("map", new CollectionNode(List.of(newEntry))));

            final ChangeNode result = strategy.compare(snapshotOf(oldRoot), snapshotOf(newRoot));
            final ContainerChangeNode mapChange = (ContainerChangeNode) ((ContainerChangeNode) result).children().get(0);
            assertEquals("map", mapChange.path());

            final ContainerChangeNode entryChange = (ContainerChangeNode) mapChange.children().get(0);
            assertEquals("map[null]", entryChange.path());

            final FieldChangeNode valueChange = (FieldChangeNode) entryChange.children().get(0);
            assertEquals("map[null].value", valueChange.path());
            assertEquals("v1", valueChange.oldValue());
            assertEquals("v2", valueChange.newValue());
        }
    }

    @Nested
    @DisplayName("循环引用安全测试")
    class CycleSafeTests {
        @Test
        @DisplayName("循环引用对象图（A->B->A）对比不应 StackOverflow 且能识别差异")
        void compare_cyclicGraph_shouldNotStackOverflowAndReportChanges() {
            final Map<String, ValueNode> oldAFields = new HashMap<>();
            final Map<String, ValueNode> oldBFields = new HashMap<>();
            final ObjectNode oldA = new ObjectNode(oldAFields);
            final ObjectNode oldB = new ObjectNode(oldBFields);
            oldAFields.put("b", oldB);
            oldBFields.put("a", oldA);
            oldBFields.put("value", new PrimitiveNode("v1"));

            final Map<String, ValueNode> newAFields = new HashMap<>();
            final Map<String, ValueNode> newBFields = new HashMap<>();
            final ObjectNode newA = new ObjectNode(newAFields);
            final ObjectNode newB = new ObjectNode(newBFields);
            newAFields.put("b", newB);
            newBFields.put("a", newA);
            newBFields.put("value", new PrimitiveNode("v2"));

            final ChangeNode result = assertDoesNotThrow(() -> strategy.compare(snapshotOf(oldA), snapshotOf(newA)));
            final ContainerChangeNode rootChange = (ContainerChangeNode) result;
            assertEquals(1, rootChange.children().size());

            final ContainerChangeNode bChange = (ContainerChangeNode) rootChange.children().get(0);
            assertEquals("b", bChange.path());
            assertEquals(1, bChange.children().size());

            final FieldChangeNode valueChange = (FieldChangeNode) bChange.children().get(0);
            assertEquals("b.value", valueChange.path());
            assertEquals("v1", valueChange.oldValue());
            assertEquals("v2", valueChange.newValue());
        }
    }

    @Nested
    @DisplayName("类型变化场景测试（跨类型 → ObjectFieldChangeNode）")
    class TypeChangeTests {

        @Test
        @DisplayName("ObjectNode 变为 PrimitiveNode 应产出 ObjectFieldChangeNode（两侧为 ValueNode 实例）")
        void objectNode_toPrimitiveNode_shouldProduceObjectFieldChange() {
            final ObjectNode oldAddress = new ObjectNode(Map.of("street", new PrimitiveNode("Main St")));
            final PrimitiveNode newAddress = new PrimitiveNode("42");
            final ObjectNode oldUser = new ObjectNode(Map.of("address", oldAddress));
            final ObjectNode newUser = new ObjectNode(Map.of("address", newAddress));

            final ChangeNode result = strategy.compare(snapshotOf(oldUser), snapshotOf(newUser));
            final ObjectFieldChangeNode change = (ObjectFieldChangeNode) ((ContainerChangeNode) result).children().get(0);

            assertEquals("address", change.path());
            assertSame(oldAddress, change.oldNode(), "oldNode 应为原始 ObjectNode 实例（快照表示）");
            assertSame(newAddress, change.newNode(), "newNode 应为原始 PrimitiveNode 实例（快照表示）");
        }

        @Test
        @DisplayName("CollectionNode 变为 ObjectNode 应产出 ObjectFieldChangeNode（两侧为 ValueNode 实例）")
        void collectionNode_toObjectNode_shouldProduceObjectFieldChange() {
            final CollectionNode oldItems = new CollectionNode(List.of(new PrimitiveNode("A")));
            final ObjectNode newItems = new ObjectNode(Map.of("id", new PrimitiveNode("1")));
            final ObjectNode oldUser = new ObjectNode(Map.of("items", oldItems));
            final ObjectNode newUser = new ObjectNode(Map.of("items", newItems));

            final ChangeNode result = strategy.compare(snapshotOf(oldUser), snapshotOf(newUser));
            final ObjectFieldChangeNode change = (ObjectFieldChangeNode) ((ContainerChangeNode) result).children().get(0);

            assertEquals("items", change.path());
            assertSame(oldItems, change.oldNode(), "oldNode 应为原始 CollectionNode 实例（快照表示）");
            assertSame(newItems, change.newNode(), "newNode 应为原始 ObjectNode 实例（快照表示）");
        }

        @Test
        @DisplayName("PrimitiveNode 变为 ObjectNode 应产出 ObjectFieldChangeNode（两侧为 ValueNode 实例）")
        void primitiveNode_toObjectNode_shouldProduceObjectFieldChange() {
            final PrimitiveNode oldAddress = new PrimitiveNode("42");
            final ObjectNode newAddress = new ObjectNode(Map.of("street", new PrimitiveNode("Main St")));
            final ObjectNode oldUser = new ObjectNode(Map.of("address", oldAddress));
            final ObjectNode newUser = new ObjectNode(Map.of("address", newAddress));

            final ChangeNode result = strategy.compare(snapshotOf(oldUser), snapshotOf(newUser));
            final ObjectFieldChangeNode change = (ObjectFieldChangeNode) ((ContainerChangeNode) result).children().get(0);

            assertEquals("address", change.path());
            assertSame(oldAddress, change.oldNode(), "oldNode 应为原始 PrimitiveNode 实例（快照表示）");
            assertSame(newAddress, change.newNode(), "newNode 应为原始 ObjectNode 实例（快照表示）");
        }

        @Test
        @DisplayName("NullNode 变为 ObjectNode 应产出 ObjectFieldChangeNode（两侧为 ValueNode 实例）")
        void nullNode_toObjectNode_shouldProduceObjectFieldChange() {
            final ObjectNode newAddress = new ObjectNode(Map.of("street", new PrimitiveNode("Main St")));
            final ObjectNode oldUser = new ObjectNode(Map.of("address", new NullNode()));
            final ObjectNode newUser = new ObjectNode(Map.of("address", newAddress));

            final ChangeNode result = strategy.compare(snapshotOf(oldUser), snapshotOf(newUser));
            final ObjectFieldChangeNode change = (ObjectFieldChangeNode) ((ContainerChangeNode) result).children().get(0);

            assertEquals("address", change.path());
            assertTrue(change.oldNode() instanceof NullNode, "oldNode 应为 NullNode");
            assertSame(newAddress, change.newNode(), "newNode 应为原始 ObjectNode 实例（快照表示）");
        }

        @Test
        @DisplayName("CollectionNode 变为 NullNode 应产出 ObjectFieldChangeNode")
        void collectionNode_toNullNode_shouldProduceObjectFieldChange() {
            final CollectionNode oldItems = new CollectionNode(List.of(new PrimitiveNode("A")));
            final ObjectNode oldUser = new ObjectNode(Map.of("items", oldItems));
            final ObjectNode newUser = new ObjectNode(Map.of("items", new NullNode()));

            final ChangeNode result = strategy.compare(snapshotOf(oldUser), snapshotOf(newUser));
            final ObjectFieldChangeNode change = (ObjectFieldChangeNode) ((ContainerChangeNode) result).children().get(0);

            assertEquals("items", change.path());
            assertSame(oldItems, change.oldNode(), "oldNode 应为原始 CollectionNode 实例（快照表示）");
            assertTrue(change.newNode() instanceof NullNode, "newNode 应为 NullNode");
        }

        @Test
        @DisplayName("NullNode 变为 CollectionNode 应产出 ObjectFieldChangeNode")
        void nullNode_toCollectionNode_shouldProduceObjectFieldChange() {
            final CollectionNode newItems = new CollectionNode(List.of(new PrimitiveNode("A")));
            final ObjectNode oldUser = new ObjectNode(Map.of("items", new NullNode()));
            final ObjectNode newUser = new ObjectNode(Map.of("items", newItems));

            final ChangeNode result = strategy.compare(snapshotOf(oldUser), snapshotOf(newUser));
            final ObjectFieldChangeNode change = (ObjectFieldChangeNode) ((ContainerChangeNode) result).children().get(0);

            assertEquals("items", change.path());
            assertTrue(change.oldNode() instanceof NullNode, "oldNode 应为 NullNode");
            assertSame(newItems, change.newNode(), "newNode 应为原始 CollectionNode 实例（快照表示）");
        }

        @Test
        @DisplayName("ObjectNode 变为 CollectionNode 应产出 ObjectFieldChangeNode")
        void objectNode_toCollectionNode_shouldProduceObjectFieldChange() {
            final ObjectNode oldAddress = new ObjectNode(Map.of("street", new PrimitiveNode("Main St")));
            final CollectionNode newAddress = new CollectionNode(List.of(new PrimitiveNode("A")));
            final ObjectNode oldUser = new ObjectNode(Map.of("address", oldAddress));
            final ObjectNode newUser = new ObjectNode(Map.of("address", newAddress));

            final ChangeNode result = strategy.compare(snapshotOf(oldUser), snapshotOf(newUser));
            final ObjectFieldChangeNode change = (ObjectFieldChangeNode) ((ContainerChangeNode) result).children().get(0);

            assertEquals("address", change.path());
            assertSame(oldAddress, change.oldNode(), "oldNode 应为原始 ObjectNode 实例（快照表示）");
            assertSame(newAddress, change.newNode(), "newNode 应为原始 CollectionNode 实例（快照表示）");
        }

        @Test
        @DisplayName("CollectionNode 变为 PrimitiveNode 应产出 ObjectFieldChangeNode")
        void collectionNode_toPrimitiveNode_shouldProduceObjectFieldChange() {
            final CollectionNode oldItems = new CollectionNode(List.of(new PrimitiveNode("A")));
            final PrimitiveNode newItems = new PrimitiveNode("x");
            final ObjectNode oldUser = new ObjectNode(Map.of("items", oldItems));
            final ObjectNode newUser = new ObjectNode(Map.of("items", newItems));

            final ChangeNode result = strategy.compare(snapshotOf(oldUser), snapshotOf(newUser));
            final ObjectFieldChangeNode change = (ObjectFieldChangeNode) ((ContainerChangeNode) result).children().get(0);

            assertEquals("items", change.path());
            assertSame(oldItems, change.oldNode(), "oldNode 应为原始 CollectionNode 实例（快照表示）");
            assertSame(newItems, change.newNode(), "newNode 应为原始 PrimitiveNode 实例（快照表示）");
        }
    }

    @Nested
    @DisplayName("扁平视图转换测试（ObjectFieldChange 双视图叶子）")
    class FlatViewTests {

        private ChangeSet changeSetOf(ValueNode oldTree, ValueNode newTree) {
            final ChangeNode tree = strategy.compare(snapshotOf(oldTree), snapshotOf(newTree));
            return new ChangeSet(List.of(new ObjectChange(new Object(), tree)));
        }

        @Test
        @DisplayName("对象字段变为 null：getAllChanges 与 getLeafChanges 均含 ObjectFieldChange（叶子）")
        void objectFieldChange_shouldAppearAsLeafInBothViews() {
            final ObjectNode oldAddress = new ObjectNode(Map.of("street", new PrimitiveNode("Main St")));
            final ObjectNode oldUser = new ObjectNode(Map.of("address", oldAddress));
            final ObjectNode newUser = new ObjectNode(Map.of("address", new NullNode()));
            final ChangeSet changeSet = changeSetOf(oldUser, newUser);

            final List<Change> allChanges = changeSet.getAllChanges();
            assertEquals(1, allChanges.size(), "getAllChanges 应只含 ObjectFieldChange 叶子（根容器不计数）");
            final ObjectFieldChange all = (ObjectFieldChange) allChanges.get(0);
            assertEquals("address", all.path());
            assertEquals("address", all.fullPath());
            assertEquals("address", all.fieldName());
            assertNull(all.collectionFieldName());
            assertFalse(all.isParentCollection());
            assertSame(oldAddress, all.oldNode(), "oldNode 应为原始 ObjectNode 实例（快照表示）");
            assertTrue(all.newNode() instanceof NullNode, "newNode 应为 NullNode");

            final List<Change> leafChanges = changeSet.getLeafChanges();
            assertEquals(1, leafChanges.size(), "getLeafChanges 应只含 ObjectFieldChange 叶子");
            final ObjectFieldChange leaf = (ObjectFieldChange) leafChanges.get(0);
            assertEquals("address", leaf.path());
            assertEquals("address", leaf.fullPath());
            assertEquals("address", leaf.fieldName());
            assertNull(leaf.collectionFieldName());
            assertFalse(leaf.isParentCollection());
            assertSame(oldAddress, leaf.oldNode());
            assertTrue(leaf.newNode() instanceof NullNode);
        }

        @Test
        @DisplayName("集合元素内字段跨类型：转换后 ObjectFieldChange 元数据正确（集合上下文）")
        void objectFieldChange_insideCollectionContext_shouldCarryMetadata() {
            final ObjectNode oldDetail = new ObjectNode(Map.of("street", new PrimitiveNode("Main St")));
            final ObjectNode oldItem = new ObjectNode(Map.of("id", new PrimitiveNode("A"), "detail", oldDetail), "A");
            final ObjectNode newItem = new ObjectNode(Map.of("id", new PrimitiveNode("A"), "detail", new NullNode()), "A");
            final ObjectNode oldRoot = new ObjectNode(Map.of("items", new CollectionNode(List.of(oldItem))));
            final ObjectNode newRoot = new ObjectNode(Map.of("items", new CollectionNode(List.of(newItem))));
            final ChangeSet changeSet = changeSetOf(oldRoot, newRoot);

            final List<Change> leafChanges = changeSet.getLeafChanges();
            assertEquals(1, leafChanges.size());
            final ObjectFieldChange leaf = (ObjectFieldChange) leafChanges.get(0);
            assertEquals("items[A].detail", leaf.path());
            assertEquals("items[A].detail", leaf.fullPath());
            assertEquals("detail", leaf.fieldName());
            assertEquals("items", leaf.collectionFieldName());
            assertFalse(leaf.isParentCollection(), "父节点是集合项对象 items[A]（非集合本身）");
            assertSame(oldDetail, leaf.oldNode());
            assertTrue(leaf.newNode() instanceof NullNode);
        }
    }

    @Nested
    @DisplayName("字段排序稳定性测试（P5：声明序输出）")
    class FieldOrderTests {

        @Test
        @DisplayName("多字段全部变更时按声明序输出（非字典序）")
        void fieldChanges_shouldFollowDeclarationOrder() {
            final Map<String, ValueNode> oldFields = new LinkedHashMap<>();
            oldFields.put("zebra", new PrimitiveNode("z1"));
            oldFields.put("alpha", new PrimitiveNode("a1"));
            oldFields.put("mango", new PrimitiveNode("m1"));
            final Map<String, ValueNode> newFields = new LinkedHashMap<>();
            newFields.put("zebra", new PrimitiveNode("z2"));
            newFields.put("alpha", new PrimitiveNode("a2"));
            newFields.put("mango", new PrimitiveNode("m2"));

            final ChangeNode result = strategy.compare(
                    snapshotOf(new ObjectNode(oldFields)),
                    snapshotOf(new ObjectNode(newFields)));

            final List<ChangeNode> children = ((ContainerChangeNode) result).children();
            assertEquals(3, children.size());
            // 声明序 zebra/alpha/mango，字典序为 alpha/mango/zebra
            assertEquals(List.of("zebra", "alpha", "mango"),
                    children.stream().map(ChangeNode::path).toList());
        }

        @Test
        @DisplayName("新增字段按声明序追加在后（old 字段声明序为基准）")
        void newField_shouldBeAppendedAfterOldDeclarationOrder() {
            final Map<String, ValueNode> oldFields = new LinkedHashMap<>();
            oldFields.put("alpha", new PrimitiveNode("a1"));
            oldFields.put("zebra", new PrimitiveNode("z1"));
            final Map<String, ValueNode> newFields = new LinkedHashMap<>();
            newFields.put("alpha", new PrimitiveNode("a2"));
            newFields.put("zebra", new PrimitiveNode("z2"));
            newFields.put("mango", new PrimitiveNode("m1"));

            final ChangeNode result = strategy.compare(
                    snapshotOf(new ObjectNode(oldFields)),
                    snapshotOf(new ObjectNode(newFields)));

            final List<ChangeNode> children = ((ContainerChangeNode) result).children();
            assertEquals(3, children.size());
            // old 声明序 alpha/zebra 为基准，新增 mango 追加在后（非字典序 alpha/mango/zebra）
            assertEquals(List.of("alpha", "zebra", "mango"),
                    children.stream().map(ChangeNode::path).toList());
        }
    }
}
