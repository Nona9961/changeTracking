package com.nona.changeTracking.domain.changeset;

import com.nona.changeTracking.domain.model.changeset.*;
import com.nona.changeTracking.domain.model.snapshot.NullNode;
import com.nona.changeTracking.domain.model.snapshot.ObjectNode;
import com.nona.changeTracking.domain.model.snapshot.PrimitiveNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ChangeSet 相关模型契约测试")
class ChangeSetModelTest {

    // --- Test Data ---
    private final FieldChangeNode fieldChangeNode = new FieldChangeNode("path.name", "old", "new");
    private final ContainerChangeNode containerNode = new ContainerChangeNode("path", List.of(fieldChangeNode));
    private final ItemAddedNode itemAddedNode = new ItemAddedNode("path.items", new PrimitiveNode("newItem"));
    private final ContainerChangeNode rootNode = new ContainerChangeNode("", List.of(containerNode, itemAddedNode));
    private final Object sampleTarget = new Object();
    private final ObjectChange objectChange = new ObjectChange(sampleTarget, rootNode);
    private ChangeSet changeSet;

    @BeforeEach
    void setup() {
        changeSet = new ChangeSet(List.of(objectChange));
    }


    @Nested
    @DisplayName("ObjectChange Record 测试")
    class ObjectChangeRecordTest {
        // ... (这部分测试保持不变) ...
    }

    @Nested
    @DisplayName("ChangeSet Class 测试")
    class ChangeSetClassTest {

        // ... (构造函数、不可变性等测试保持不变) ...

        @Test
        @DisplayName("getAllChanges() 应返回包含容器和叶子节点的扁平列表")
        void getAllChanges_shouldReturnFlatListOfAllNodes() {
            final List<Change> allChanges = changeSet.getAllChanges();

            // 预期结果: 根节点(Container), 容器节点(Container), 字段变更(Field), 新增项(ItemAdded)
            // 注意：根节点 "" 我们通常不关心，所以实现时可以跳过
            assertEquals(3, allChanges.size());
            assertTrue(allChanges.stream().anyMatch(c -> c.path().equals("path") && c instanceof ContainerChange));
            assertTrue(allChanges.stream().anyMatch(c -> c.path().equals("path.name") && c instanceof ValueChange));
            assertTrue(allChanges.stream().anyMatch(c -> c.path().equals("path.items") && c instanceof ItemAddedChange));
        }

        @Test
        @DisplayName("getLeafChanges() 应只返回最细粒度的叶子节点（path 为完整路径）")
        void getLeafChanges_shouldReturnOnlyLeafNodes() {
            final List<Change> leafChanges = changeSet.getLeafChanges();

            // 预期结果: 字段变更(Field), 新增项(ItemAdded)
            assertEquals(2, leafChanges.size());
            assertTrue(leafChanges.stream().anyMatch(c -> c.path().equals("path.name") && c instanceof ValueChange));
            assertTrue(leafChanges.stream().anyMatch(c -> c.path().equals("path.items") && c instanceof ItemAddedChange));
            // 确保没有任何容器节点
            assertFalse(leafChanges.stream().anyMatch(c -> c instanceof ContainerChange));
        }

        @Test
        @DisplayName("对于空的 ChangeSet，视图方法应返回空列表")
        void viewMethods_onEmptyChangeSet_shouldReturnEmptyList() {
            final ChangeSet emptyChangeSet = new ChangeSet(Collections.emptyList());
            assertTrue(emptyChangeSet.getAllChanges().isEmpty());
            assertTrue(emptyChangeSet.getLeafChanges().isEmpty());
        }

        @Test
        @DisplayName("ContainerChange 的 children 应包含相对路径而非完整路径")
        void containerChange_children_shouldHaveRelativePaths() {
            final List<Change> allChanges = changeSet.getAllChanges();

            // 找到 path 为 "path" 的 ContainerChange
            final ContainerChange container = allChanges.stream()
                    .filter(c -> c instanceof ContainerChange && c.path().equals("path"))
                    .map(c -> (ContainerChange) c)
                    .findFirst()
                    .orElseThrow();

            // children 应该包含相对路径 "name"，而不是完整路径 "path.name"
            assertEquals(1, container.children().size());
            assertEquals("name", container.children().get(0).path());
        }
    }

    @Nested
    @DisplayName("A9 转换去重：多级嵌套树视图契约测试")
    class NestedTreeViewContractTest {

        // 模拟真实 diffNode 输出格式的多级嵌套变更树：
        // "" (root ContainerChangeNode)
        // ├── "status"                  (FieldChangeNode)          叶子
        // ├── "items"                   (ContainerChangeNode)      容器
        // │   ├── "items[100]"          (ItemAddedNode)            叶子
        // │   └── "items[200].name"     (FieldChangeNode)          叶子
        // └── "address"                 (ContainerChangeNode)      容器
        //     ├── "address.city"        (FieldChangeNode)          叶子
        //     ├── "address.items"       (ContainerChangeNode)      容器
        //     │   └── "address.items[5].zip"  (FieldChangeNode)    叶子
        //     └── "address.coords"      (ObjectFieldChangeNode)    叶子
        private final ChangeNode nestedTree = new ContainerChangeNode("", List.of(
                new FieldChangeNode("status", "PENDING", "CONFIRMED"),
                new ContainerChangeNode("items", List.of(
                        new ItemAddedNode("items[100]", new PrimitiveNode("SKU-X")),
                        new FieldChangeNode("items[200].name", "旧名", "新名")
                )),
                new ContainerChangeNode("address", List.of(
                        new FieldChangeNode("address.city", "A市", "B市"),
                        new ContainerChangeNode("address.items", List.of(
                                new FieldChangeNode("address.items[5].zip", "100000", "200000")
                        )),
                        new ObjectFieldChangeNode("address.coords", new NullNode(),
                                new ObjectNode(Map.of("lat", new PrimitiveNode(1))))
                ))
        ));
        private final ChangeSet nestedChangeSet =
                new ChangeSet(List.of(new ObjectChange(new Object(), nestedTree)));

        @Test
        @DisplayName("getAllChanges() 每个容器和每个叶子恰好出现一次（无重复无缺失）")
        void getAllChanges_eachContainerAndLeaf_shouldAppearExactlyOnce() {
            final List<Change> allChanges = nestedChangeSet.getAllChanges();

            // 容器：items / address / address.items（根 "" 跳过）——各恰好一次，前序顺序
            final List<Change> containers = allChanges.stream()
                    .filter(c -> c instanceof ContainerChange)
                    .toList();
            assertEquals(3, containers.size());
            assertEquals(List.of("items", "address", "address.items"),
                    containers.stream().map(Change::path).toList());

            // 叶子：status / items[100] / items[200].name / address.city /
            //       address.items[5].zip / address.coords——各恰好一次，前序顺序
            final List<Change> leaves = allChanges.stream()
                    .filter(c -> !(c instanceof ContainerChange))
                    .toList();
            assertEquals(6, leaves.size());
            assertEquals(List.of("status", "items[100]", "items[200].name",
                            "address.city", "address.items[5].zip", "address.coords"),
                    leaves.stream().map(Change::path).toList());

            // 扁平列表无重复路径
            assertEquals(9, allChanges.size());
            assertEquals(9, allChanges.stream().map(Change::path).distinct().count());
        }

        @Test
        @DisplayName("getLeafChanges() 应只返回叶子（含 ObjectFieldChange），path 与 fullPath 一致")
        void getLeafChanges_shouldReturnOnlyLeavesWithFullPaths() {
            final List<Change> leafChanges = nestedChangeSet.getLeafChanges();

            assertEquals(6, leafChanges.size());
            assertFalse(leafChanges.stream().anyMatch(c -> c instanceof ContainerChange));
            // 扁平视图：每个叶子 path 与 fullPath 一致（完整路径）
            for (final Change leaf : leafChanges) {
                assertEquals(leaf.fullPath(), leaf.path());
            }
            assertTrue(leafChanges.stream().anyMatch(c -> c instanceof ObjectFieldChange));
        }

        @Test
        @DisplayName("树形 children 视图：相对路径 + 上下文元数据（collectionFieldName / isParentCollection）")
        void containerChildren_shouldCarryRelativePathsAndContextMetadata() {
            final ContainerChange itemsContainer = nestedChangeSet.getAllChanges().stream()
                    .filter(c -> c instanceof ContainerChange && c.path().equals("items"))
                    .map(c -> (ContainerChange) c)
                    .findFirst()
                    .orElseThrow();

            assertEquals(2, itemsContainer.children().size());

            final ItemAddedChange added = (ItemAddedChange) itemsContainer.children().get(0);
            assertEquals("[100]", added.path());
            assertEquals("items[100]", added.fullPath());
            assertNull(added.fieldName());
            assertEquals("items", added.collectionFieldName());
            assertTrue(added.isParentCollection());

            final ValueChange renamed = (ValueChange) itemsContainer.children().get(1);
            assertEquals("[200].name", renamed.path());
            assertEquals("items[200].name", renamed.fullPath());
            // 现状：相对路径以 "[" 开头时 fieldName 直接为 null（extractFieldName 首段判断）
            assertNull(renamed.fieldName());
            assertEquals("items", renamed.collectionFieldName());
            assertTrue(renamed.isParentCollection());
        }

        @Test
        @DisplayName("扁平叶子视图：完整路径 + 上下文元数据（含深层嵌套集合）")
        void leafChanges_shouldCarryFullPathsAndContextMetadata() {
            final List<Change> leafChanges = nestedChangeSet.getLeafChanges();

            // 集合项新增：path=fullPath，fieldName=null（纯索引项），最近集合=items
            final ItemAddedChange added = (ItemAddedChange) leafChanges.stream()
                    .filter(c -> c instanceof ItemAddedChange)
                    .findFirst()
                    .orElseThrow();
            assertEquals("items[100]", added.path());
            assertEquals("items[100]", added.fullPath());
            assertNull(added.fieldName());
            assertEquals("items", added.collectionFieldName());
            assertTrue(added.isParentCollection());

            // 深层嵌套集合内的字段：最近集合字段名为 items（不含路径前缀）
            final ValueChange zip = (ValueChange) leafChanges.stream()
                    .filter(c -> c.path().equals("address.items[5].zip"))
                    .findFirst()
                    .orElseThrow();
            // 现状：扁平视图 fieldName 由相对路径（"[5].zip"）计算，以 "[" 开头 → null
            assertNull(zip.fieldName());
            assertEquals("items", zip.collectionFieldName());
            assertTrue(zip.isParentCollection());

            // 主表字段：无集合上下文
            final ValueChange status = (ValueChange) leafChanges.stream()
                    .filter(c -> c.path().equals("status"))
                    .findFirst()
                    .orElseThrow();
            assertEquals("status", status.fieldName());
            assertNull(status.collectionFieldName());
            assertFalse(status.isParentCollection());

            // 对象整体替换（ObjectFieldChange）：叶子，主表字段无集合上下文
            final ObjectFieldChange coords = (ObjectFieldChange) leafChanges.stream()
                    .filter(c -> c instanceof ObjectFieldChange)
                    .findFirst()
                    .orElseThrow();
            assertEquals("address.coords", coords.path());
            assertEquals("coords", coords.fieldName());
            assertNull(coords.collectionFieldName());
            assertFalse(coords.isParentCollection());
        }
    }
}
