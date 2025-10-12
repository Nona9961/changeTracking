package com.nona.changeTracking.api;

import com.nona.changeTracking.domain.model.changeset.ChangeSet;
import com.nona.changeTracking.domain.model.unitofwork.UnitOfWork;
import com.nona.changeTracking.spi.CreationContext;
import com.nona.changeTracking.spi.SnapshotStrategy;
import com.nona.changeTracking.spi.SnapshotStrategyProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("UnitOfWorkFactory 测试")
class UnitOfWorkFactoryTest {

    // --- Test Data & Mocks ---
    static class User {
        String name;
        User(String name) { this.name = name; }
    }

    @Test
    @DisplayName("默认构建器应能创建一个功能完备的 UnitOfWork")
    void defaultBuilder_shouldCreateFunctionalUnitOfWork() {
        // --- Arrange ---
        // 使用默认配置构建 UnitOfWork
        final UnitOfWork uow = UnitOfWorkFactory.builder().withDefaults().build();
        assertNotNull(uow, "创建的 UnitOfWork 实例不应为 null");

        final User user = new User("Alice");
        uow.registerClean(user);
        user.name = "Alicia"; // 修改对象

        // --- Act ---
        final ChangeSet changeSet = uow.calculateChanges();

        // --- Assert ---
        // 这是一个端到端的冒烟测试，验证默认组件被正确接线
        assertFalse(changeSet.isEmpty(), "应检测到变更");
        assertEquals(1, changeSet.getDirtyObjects().size());
        assertEquals("name", changeSet.getDirtyObjects().get(0).fieldChanges().get(0).fieldName());
    }

    @Test
    @DisplayName("选择一个不存在的快照策略时应抛出异常")
    void build_shouldThrowExceptionForNonExistentStrategy() {
        // --- Arrange ---
        final UnitOfWorkFactory.Builder builder = UnitOfWorkFactory.builder().withDefaults();

        // --- Act & Assert ---
        final var exception = assertThrows(IllegalArgumentException.class, () -> {
            builder.snapshotStrategy("non-existent-strategy").build();
        });

        assertTrue(exception.getMessage().contains("non-existent-strategy"));
    }
}
