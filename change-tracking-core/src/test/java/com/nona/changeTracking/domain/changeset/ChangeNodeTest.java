package com.nona.changeTracking.domain.changeset;

import com.nona.changeTracking.domain.model.changeset.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ChangeNode 模型结构测试")
class ChangeNodeTest {

    @Test
    @DisplayName("FieldChangeNode 应能正确存储字段变更信息")
    void fieldChangeNode_shouldHoldFieldChangeData() {
        final String path = "shippingAddress.street";
        final String oldValue = "123 Main St";
        final String newValue = "456 Market St";

        final FieldChangeNode node = new FieldChangeNode(path, oldValue, newValue);

        assertAll(
                () -> assertEquals(path, node.path()),
                () -> assertEquals(oldValue, node.oldValue()),
                () -> assertEquals(newValue, node.newValue())
        );
    }

    @Test
    @DisplayName("ContainerChangeNode 应能正确存储子变更节点")
    void containerChangeNode_shouldHoldChildNodes() {
        final String path = "shippingAddress";
        final List<ChangeNode> children = List.of(new FieldChangeNode("shippingAddress.street", "a", "b"));

        final ContainerChangeNode node = new ContainerChangeNode(path, children);

        assertAll(
                () -> assertEquals(path, node.path()),
                () -> assertSame(children, node.children())
        );
    }

    @Test
    @DisplayName("ItemAddedNode 应能正确存储新增的列表项")
    void itemAddedNode_shouldHoldAddedItem() {
        final String path = "items";
        final Object newItem = new Object();

        final ItemAddedNode node = new ItemAddedNode(path, newItem);

        assertAll(
                () -> assertEquals(path, node.path()),
                () -> assertSame(newItem, node.addedItem())
        );
    }

    @Test
    @DisplayName("ItemRemovedNode 应能正确存储移除的列表项")
    void itemRemovedNode_shouldHoldRemovedItem() {
        final String path = "items";
        final Object removedItem = new Object();

        final ItemRemovedNode node = new ItemRemovedNode(path, removedItem);

        assertAll(
                () -> assertEquals(path, node.path()),
                () -> assertSame(removedItem, node.removedItem())
        );
    }
}
