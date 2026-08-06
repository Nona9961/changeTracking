package com.nona.changeTracking.domain.changeset;

import com.nona.changeTracking.domain.model.changeset.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Change (扁平化视图) 模型结构测试")
class ChangeTest {

    @Nested
    @DisplayName("基本结构测试")
    class BasicStructureTests {

        @Test
        @DisplayName("FieldChange 应能正确存储字段变更信息")
        void fieldChange_shouldHoldFieldChangeData() {
            final FieldChange change = new FieldChange("path", "full.path", "path", null, false, "old", "new");
            assertEquals("path", change.path());
            assertEquals("full.path", change.fullPath());
            assertEquals("path", change.fieldName());
            assertNull(change.collectionFieldName());
            assertFalse(change.isParentCollection());
            assertEquals("old", change.oldValue());
            assertEquals("new", change.newValue());
        }

        @Test
        @DisplayName("ContainerChange 应能正确存储路径和子变更")
        void containerChange_shouldHoldPathAndChildren() {
            final List<Change> children = List.of(
                    new FieldChange("child.path", "parent.child.path", "path", null, false, "a", "b")
            );
            final ContainerChange change = new ContainerChange("path", "full.path", "path", null, false, children);
            assertEquals("path", change.path());
            assertEquals("full.path", change.fullPath());
            assertEquals("path", change.fieldName());
            assertNull(change.collectionFieldName());
            assertFalse(change.isParentCollection());
            assertSame(children, change.children());
        }

        @Test
        @DisplayName("ContainerChange 构造时防御拷贝子列表（外部修改不影响节点）")
        void containerChange_shouldCopyChildrenOnConstruction() {
            final List<Change> children = new ArrayList<>(List.of(
                    new FieldChange("child.path", "parent.child.path", "path", null, false, "a", "b")
            ));
            final ContainerChange change = new ContainerChange("path", "full.path", "path", null, false, children);

            children.add(new FieldChange("other.path", "parent.other.path", "other", null, false, "a", "b"));

            assertEquals(1, change.children().size(), "构造后外部修改不应影响节点");
        }

        @Test
        @DisplayName("ItemAddedChange 应能正确存储新增项信息")
        void itemAddedChange_shouldHoldAddedItemData() {
            final Object addedItem = new Object();
            final ItemAddedChange change = new ItemAddedChange("[1]", "items[1]", null, "items", true, addedItem);
            assertEquals("[1]", change.path());
            assertEquals("items[1]", change.fullPath());
            assertNull(change.fieldName());
            assertEquals("items", change.collectionFieldName());
            assertTrue(change.isParentCollection());
            assertSame(addedItem, change.addedItem());
        }

        @Test
        @DisplayName("ItemRemovedChange 应能正确存储删除项信息")
        void itemRemovedChange_shouldHoldRemovedItemData() {
            final Object removedItem = new Object();
            final ItemRemovedChange change = new ItemRemovedChange("[1]", "items[1]", null, "items", true, removedItem);
            assertEquals("[1]", change.path());
            assertEquals("items[1]", change.fullPath());
            assertNull(change.fieldName());
            assertEquals("items", change.collectionFieldName());
            assertTrue(change.isParentCollection());
            assertSame(removedItem, change.removedItem());
        }
    }

    @Nested
    @DisplayName("fieldName() 方法测试")
    class FieldNameTests {

        @Test
        @DisplayName("普通字段路径应返回字段名")
        void fieldName_simpleField_shouldReturnFieldName() {
            final FieldChange change = new FieldChange("name", "name", "name", null, false, "old", "new");
            assertEquals("name", change.fieldName());
        }

        @Test
        @DisplayName("带索引的路径应返回纯字段名")
        void fieldName_pathWithIndex_shouldReturnFieldNameWithoutIndex() {
            final ContainerChange change = new ContainerChange("items[1]", "items[1]", "items", null, false, List.of());
            assertEquals("items", change.fieldName());
        }

        @Test
        @DisplayName("纯索引路径应返回 null")
        void fieldName_pureIndexPath_shouldReturnNull() {
            final ItemAddedChange change = new ItemAddedChange("[1]", "items[1]", null, "items", true, new Object());
            assertNull(change.fieldName());
        }
    }

    @Nested
    @DisplayName("collectionFieldName() 方法测试")
    class CollectionFieldNameTests {

        @Test
        @DisplayName("主表字段应返回 null")
        void collectionFieldName_rootField_shouldReturnNull() {
            final FieldChange change = new FieldChange("status", "status", "status", null, false, "old", "new");
            assertNull(change.collectionFieldName());
        }

        @Test
        @DisplayName("一层集合内的字段应返回集合字段名")
        void collectionFieldName_singleLevelCollection_shouldReturnCollectionName() {
            final FieldChange change = new FieldChange("name", "items[1].name", "name", "items", true, "old", "new");
            assertEquals("items", change.collectionFieldName());
        }

        @Test
        @DisplayName("多层嵌套集合应返回最近的集合字段名")
        void collectionFieldName_nestedCollection_shouldReturnNearestCollectionName() {
            final FieldChange change = new FieldChange("value", "items[1].subItems[101].value", "value", "subItems", true, "old", "new");
            assertEquals("subItems", change.collectionFieldName());
        }

        @Test
        @DisplayName("深层嵌套集合项应返回最近的集合字段名")
        void collectionFieldName_deepNestedCollectionItem_shouldReturnNearestCollectionName() {
            // 测试 items[1].subItems[101].specs[1001].value 的场景
            final FieldChange change = new FieldChange("value", "items[1].subItems[101].specs[1001].value", "value", "specs", true, "old", "new");
            assertEquals("specs", change.collectionFieldName());
        }
    }

    @Nested
    @DisplayName("isParentCollection() 方法测试")
    class IsParentCollectionTests {

        @Test
        @DisplayName("主表字段父节点不是集合")
        void isParentCollection_rootField_shouldReturnFalse() {
            final FieldChange change = new FieldChange("status", "status", "status", null, false, "old", "new");
            assertFalse(change.isParentCollection());
        }

        @Test
        @DisplayName("集合项的子字段父节点是集合")
        void isParentCollection_collectionItemField_shouldReturnTrue() {
            final FieldChange change = new FieldChange("name", "items[1].name", "name", "items", true, "old", "new");
            assertTrue(change.isParentCollection());
        }

        @Test
        @DisplayName("集合项本身父节点是集合")
        void isParentCollection_collectionItem_shouldReturnTrue() {
            final ItemAddedChange change = new ItemAddedChange("[1]", "items[1]", null, "items", true, new Object());
            assertTrue(change.isParentCollection());
        }
    }
}
