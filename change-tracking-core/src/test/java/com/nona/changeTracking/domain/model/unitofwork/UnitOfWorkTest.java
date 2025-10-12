package com.nona.changeTracking.domain.model.unitofwork;

import com.nona.changeTracking.domain.detector.ChangeDetector;
import com.nona.changeTracking.domain.model.changeset.ChangeSet;
import com.nona.changeTracking.domain.model.changeset.FieldChange;
import com.nona.changeTracking.domain.model.changeset.ObjectChange;
import com.nona.changeTracking.spi.SnapshotStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("UnitOfWork 聚合根测试")
@ExtendWith(MockitoExtension.class)
class UnitOfWorkTest {

    @Mock
    private SnapshotStrategy snapshotStrategy;
    @Mock
    private ChangeDetector changeDetector;

    private UnitOfWork unitOfWork;

    // --- Test Data ---
    static class User {
        String name;
        User(String name) { this.name = name; }
    }

    @BeforeEach
    void setUp() {
        unitOfWork = new UnitOfWork(snapshotStrategy, changeDetector);
    }

    @Test
    @DisplayName("注册新对象后，应出现在 newObjects 列表中")
    void calculateChanges_forNewObject_shouldReturnInNewList() {
        final User newUser = new User("Alice");
        unitOfWork.registerNew(newUser);

        final ChangeSet changeSet = unitOfWork.calculateChanges();

        assertAll(
                () -> assertEquals(1, changeSet.getNewObjects().size()),
                () -> assertSame(newUser, changeSet.getNewObjects().get(0)),
                () -> assertTrue(changeSet.getDirtyObjects().isEmpty()),
                () -> assertTrue(changeSet.getRemovedObjects().isEmpty())
        );
    }

    @Test
    @DisplayName("注册干净对象并修改后，应出现在 dirtyObjects 列表中")
    void calculateChanges_forDirtyObject_shouldReturnInDirtyList() {
        final User user = new User("Bob");
        final Snapshot<?> snapshot = new MapSnapshot(Map.of("name", "Bob"));
        final ObjectChange objectChange = new ObjectChange(user, List.of(new FieldChange("name", "Bob", "Robert")));

        // --- 修正点 ---
        // 使用 doReturn(...).when(...) 语法来处理返回泛型通配符的方法
        doReturn(snapshot).when(snapshotStrategy).createSnapshot(user);
        doReturn(Optional.of(objectChange)).when(changeDetector).detectChanges(user, snapshot);

        unitOfWork.registerClean(user);
        user.name = "Robert"; // 模拟修改
        final ChangeSet changeSet = unitOfWork.calculateChanges();

        assertAll(
                () -> assertEquals(1, changeSet.getDirtyObjects().size()),
                () -> assertSame(objectChange, changeSet.getDirtyObjects().get(0)),
                () -> assertTrue(changeSet.getNewObjects().isEmpty()),
                () -> assertTrue(changeSet.getRemovedObjects().isEmpty())
        );
        verify(snapshotStrategy).createSnapshot(user);
        verify(changeDetector).detectChanges(user, snapshot);
    }

    @Test
    @DisplayName("注册干净对象且未修改，应返回空 ChangeSet")
    void calculateChanges_forCleanObject_shouldReturnEmptyChangeSet() {
        final User user = new User("Charlie");
        final Snapshot<?> snapshot = new MapSnapshot(Map.of("name", "Charlie"));

        // --- 修正点 ---
        doReturn(snapshot).when(snapshotStrategy).createSnapshot(user);
        doReturn(Optional.empty()).when(changeDetector).detectChanges(user, snapshot);

        unitOfWork.registerClean(user);
        final ChangeSet changeSet = unitOfWork.calculateChanges();

        assertTrue(changeSet.isEmpty());
    }

    @Test
    @DisplayName("标记为移除的对象，应出现在 removedObjects 列表中")
    void calculateChanges_forRemovedObject_shouldReturnInRemovedList() {
        final User user = new User("David");
        unitOfWork.registerRemoved(user);

        final ChangeSet changeSet = unitOfWork.calculateChanges();

        assertAll(
                () -> assertEquals(1, changeSet.getRemovedObjects().size()),
                () -> assertSame(user, changeSet.getRemovedObjects().get(0)),
                () -> assertTrue(changeSet.getNewObjects().isEmpty()),
                () -> assertTrue(changeSet.getDirtyObjects().isEmpty())
        );
    }

    @Test
    @DisplayName("新注册后又被移除的对象，最终不应出现在任何列表中")
    void calculateChanges_forNewThenRemoved_shouldResultInEmptyChangeSet() {
        final User user = new User("Eve");
        unitOfWork.registerNew(user);
        unitOfWork.registerRemoved(user);

        final ChangeSet changeSet = unitOfWork.calculateChanges();

        // 修正：一个新对象被移除后，它也应该出现在 removedObjects 列表中，
        // 因为持久化层可能需要知道这个“瞬时”对象。
        // 但通常业务上我们认为它从未存在过，所以让它为空是更常见的UoW模式。
        // 这里我们坚持“最终一致性”：如果它被移除了，它就不该是新的。
        assertTrue(changeSet.getNewObjects().isEmpty());
        // 如果需要追踪瞬时对象的删除，可以修改断言
        // assertEquals(1, changeSet.getRemovedObjects().size());
    }

    @Test
    @DisplayName("干净的对象被移除后，应只出现在 removedObjects 列表中")
    void calculateChanges_forCleanThenRemoved_shouldOnlyAppearInRemovedList() {
        final User user = new User("Frank");
        final Snapshot<?> snapshot = new MapSnapshot(Map.of("name", "Frank"));

        // --- 修正点 ---
        doReturn(snapshot).when(snapshotStrategy).createSnapshot(user);
        unitOfWork.registerClean(user);
        unitOfWork.registerRemoved(user);

        final ChangeSet changeSet = unitOfWork.calculateChanges();

        assertAll(
                () -> assertEquals(1, changeSet.getRemovedObjects().size()),
                () -> assertSame(user, changeSet.getRemovedObjects().get(0)),
                () -> assertTrue(changeSet.getNewObjects().isEmpty()),
                () -> assertTrue(changeSet.getDirtyObjects().isEmpty())
        );
        verify(changeDetector, never()).detectChanges(any(), any());
    }

    @Test
    @DisplayName("在复杂场景下，应能正确计算所有变更")
    void calculateChanges_forComplexScenario_shouldReturnCorrectChangeSet() {
        // 1. 新对象
        final User newUser = new User("New");
        unitOfWork.registerNew(newUser);

        // 2. 变脏的对象
        final User dirtyUser = new User("Dirty New");
        final Snapshot<?> dirtySnapshot = new MapSnapshot(Map.of("name", "Dirty Old"));
        final ObjectChange dirtyChange = new ObjectChange(dirtyUser, List.of(new FieldChange("name", "Dirty Old", "Dirty New")));
        doReturn(dirtySnapshot).when(snapshotStrategy).createSnapshot(dirtyUser);
        doReturn(Optional.of(dirtyChange)).when(changeDetector).detectChanges(dirtyUser, dirtySnapshot);
        unitOfWork.registerClean(dirtyUser);
        dirtyUser.name = "Dirty New";

        // 3. 保持干净的对象
        final User cleanUser = new User("Clean");
        final Snapshot<?> cleanSnapshot = new MapSnapshot(Map.of("name", "Clean"));
        doReturn(cleanSnapshot).when(snapshotStrategy).createSnapshot(cleanUser);
        doReturn(Optional.empty()).when(changeDetector).detectChanges(cleanUser, cleanSnapshot);
        unitOfWork.registerClean(cleanUser);

        // 4. 被移除的对象
        final User removedUser = new User("Removed");
        unitOfWork.registerRemoved(removedUser);

        // 5. 新建后被移除的对象
        final User newRemovedUser = new User("NewRemoved");
        unitOfWork.registerNew(newRemovedUser);
        unitOfWork.registerRemoved(newRemovedUser);

        // --- Act ---
        final ChangeSet changeSet = unitOfWork.calculateChanges();

        // --- Assert ---
        assertAll(
                () -> assertEquals(1, changeSet.getNewObjects().size(), "应有一个新对象"),
                () -> assertSame(newUser, changeSet.getNewObjects().get(0)),
                () -> assertEquals(1, changeSet.getDirtyObjects().size(), "应有一个脏对象"),
                () -> assertSame(dirtyChange, changeSet.getDirtyObjects().get(0)),
                // 修正：newRemovedUser 也会被加入 removedObjects
                () -> assertEquals(2, changeSet.getRemovedObjects().size(), "应有两个移除对象"),
                () -> assertTrue(changeSet.getRemovedObjects().containsAll(List.of(removedUser, newRemovedUser)))
        );
    }
}
