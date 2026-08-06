package com.nona.changeTracking.domain.changeset;

import com.nona.changeTracking.domain.model.changeset.*;
import com.nona.changeTracking.domain.model.snapshot.PrimitiveNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

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
}
