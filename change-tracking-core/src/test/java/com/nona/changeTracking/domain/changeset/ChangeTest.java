package com.nona.changeTracking.domain.changeset;

import com.nona.changeTracking.domain.model.changeset.Change;
import com.nona.changeTracking.domain.model.changeset.ContainerChange;
import com.nona.changeTracking.domain.model.changeset.FieldChange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

@DisplayName("Change (扁平化视图) 模型结构测试")
class ChangeTest {

    @Test
    @DisplayName("FieldChange 应能正确存储字段变更信息")
    void fieldChange_shouldHoldFieldChangeData() {
        final FieldChange change = new FieldChange("path", "old", "new");
        assertEquals("path", change.path());
        assertEquals("old", change.oldValue());
        assertEquals("new", change.newValue());
    }

    @Test
    @DisplayName("ContainerChange 应能正确存储路径和子变更")
    void containerChange_shouldHoldPathAndChildren() {
        final List<Change> children = List.of(new FieldChange("child.path", "a", "b"));
        final ContainerChange change = new ContainerChange("path", children);
        assertEquals("path", change.path());
        assertSame(children, change.children());
    }

    // ... ItemAddedChange 和 ItemRemovedChange 的测试与 ChangeNode 类似 ...
}
