package com.nona.changeTracking.domain.model.unitofwork;

import com.nona.changeTracking.domain.capability.ComparisonStrategy;
import com.nona.changeTracking.domain.capability.TrackingCapability;
import com.nona.changeTracking.domain.capability.TrackingConfiguration;
import com.nona.changeTracking.internal.capability.DefaultTrackingCapability;
import com.nona.changeTracking.spi.SnapshotStrategy;
import com.nona.changeTracking.domain.model.changeset.ChangeNode;
import com.nona.changeTracking.domain.model.changeset.ChangeSet;
import com.nona.changeTracking.domain.model.changeset.ContainerChangeNode;
import com.nona.changeTracking.domain.model.changeset.FieldChangeNode;
import com.nona.changeTracking.domain.model.snapshot.PrimitiveNode;
import com.nona.changeTracking.domain.model.snapshot.Snapshot;
import com.nona.changeTracking.domain.model.snapshot.ValueNodeSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("UnitOfWork 聚合测试")
@ExtendWith(MockitoExtension.class)
class UnitOfWorkTest {

    @Mock(lenient = true)
    private TrackingCapability<ValueNodeSnapshot> capability;
    @Mock(lenient = true)
    private SnapshotStrategy<ValueNodeSnapshot> snapshotStrategy;
    @Mock(lenient = true)
    private ComparisonStrategy<ValueNodeSnapshot> comparisonStrategy;
    @Mock(lenient = true)
    private TrackingCapability<ValueNodeSnapshot> mismatchedCapability;
    @Mock(lenient = true)
    private SnapshotStrategy<ValueNodeSnapshot> fakeSnapshotStrategy;
    @Mock(lenient = true)
    private ComparisonStrategy<ValueNodeSnapshot> fakeComparisonStrategy;

    private UnitOfWork uow;

    // --- Test Data ---
    static class User { String name; }
    private final User user1 = new User();
    private final User user2 = new User();
    private final ValueNodeSnapshot oldSnapshot = new ValueNodeSnapshot(null);
    private final ValueNodeSnapshot newSnapshot = new ValueNodeSnapshot(null);
    private final ChangeNode changeTree = new ContainerChangeNode("user", List.of(new FieldChangeNode("user.name", "a", "b")));
    private final ChangeNode noChangeTree = new ContainerChangeNode("", Collections.emptyList());


    @BeforeEach
    void setUp() {
        when(capability.getSnapshotStrategy()).thenReturn(snapshotStrategy);
        doReturn(comparisonStrategy).when(capability).getComparisonStrategy();
        when(comparisonStrategy.getSupportedSnapshotType()).thenReturn(ValueNodeSnapshot.class);
        uow = new UnitOfWork(capability);
    }

    @Nested
    @DisplayName("基本变更检测")
    class BasicChangeDetection {

        @Test
        @DisplayName("对于已变更的 clean 对象，应调用比较策略并生成 ChangeSet")
        void calculateChanges_forDirtyCleanObject_shouldCallComparisonAndCreateChangeSet() {
            // --- Arrange ---
            doReturn(oldSnapshot, newSnapshot).when(snapshotStrategy).createSnapshot(user1);
            uow.registerClean(user1);

            when(comparisonStrategy.compare(oldSnapshot, newSnapshot)).thenReturn(changeTree);

            // --- Act ---
            final ChangeSet changeSet = uow.calculateChanges();

            // --- Assert ---
            assertFalse(changeSet.isEmpty());
            assertEquals(1, changeSet.changes().size());
            assertEquals(user1, changeSet.changes().get(0).target());
            assertEquals(changeTree, changeSet.changes().get(0).changeTree());

            verify(snapshotStrategy, times(2)).createSnapshot(user1);
            verify(comparisonStrategy, times(1)).compare(oldSnapshot, newSnapshot);
        }

        @Test
        @DisplayName("连续两次 calculateChanges 应返回相同的变更集（幂等视图特征：不更新基线）")
        void calculateChanges_repeatedCalls_shouldReturnSameChangeSet() {
            doReturn(oldSnapshot, newSnapshot).when(snapshotStrategy).createSnapshot(user1);
            uow.registerClean(user1);
            when(comparisonStrategy.compare(oldSnapshot, newSnapshot)).thenReturn(changeTree);

            final ChangeSet firstCall = uow.calculateChanges();
            final ChangeSet secondCall = uow.calculateChanges();

            // 特征：calculateChanges 是幂等视图，不更新基线，重复调用产出相同变更集
            assertEquals(firstCall, secondCall);
            assertEquals(1, secondCall.changes().size());
            verify(comparisonStrategy, times(2)).compare(oldSnapshot, newSnapshot);
        }

        @Test
        @DisplayName("对于未变更的 clean 对象，不应生成变更")
        void calculateChanges_forUnchangedCleanObject_shouldNotCreateChange() {
            // --- Arrange ---
            doReturn(oldSnapshot, oldSnapshot).when(snapshotStrategy).createSnapshot(user1);
            uow.registerClean(user1);

            when(comparisonStrategy.compare(oldSnapshot, oldSnapshot)).thenReturn(noChangeTree);

            // --- Act ---
            final ChangeSet changeSet = uow.calculateChanges();

            // --- Assert ---
            assertTrue(changeSet.isEmpty());
            verify(comparisonStrategy, times(1)).compare(oldSnapshot, oldSnapshot);
        }

        @Test
        @DisplayName("对于 new 对象，不应调用比较策略，且不生成变更")
        void calculateChanges_forNewObject_shouldNotCallComparisonAndNotCreateChange() {
            uow.registerNew(user1);
            final ChangeSet changeSet = uow.calculateChanges();
            assertTrue(changeSet.isEmpty());
            verifyNoInteractions(snapshotStrategy);
            // 契约：new 对象不触发快照比较（setUp 中类型守卫 stub 不算交互）
            verify(comparisonStrategy, never()).compare(any(), any());
        }

        @Test
        @DisplayName("对于 removed 对象，不应调用比较策略，且不生成变更")
        void calculateChanges_forRemovedObject_shouldNotCallComparisonAndNotCreateChange() {
            doReturn(oldSnapshot).when(snapshotStrategy).createSnapshot(user1);
            uow.registerClean(user1);
            uow.registerRemoved(user1);
            final ChangeSet changeSet = uow.calculateChanges();
            assertTrue(changeSet.isEmpty());
            verify(comparisonStrategy, never()).compare(any(), any());
        }
    }

    @Nested
    @DisplayName("重复注册行为")
    class DuplicateRegistration {

        @Test
        @DisplayName("重复注册 clean 对象应被忽略")
        void registerClean_duplicate_shouldBeIgnored() {
            doReturn(oldSnapshot).when(snapshotStrategy).createSnapshot(user1);

            uow.registerClean(user1);
            uow.registerClean(user1); // 重复注册

            // 只应调用一次快照创建
            verify(snapshotStrategy, times(1)).createSnapshot(user1);
        }

        @Test
        @DisplayName("已注册为 clean 的对象再注册为 new 应被忽略")
        void registerNew_afterClean_shouldBeIgnored() {
            doReturn(oldSnapshot, newSnapshot).when(snapshotStrategy).createSnapshot(user1);

            uow.registerClean(user1);
            uow.registerNew(user1); // 尝试再注册为 new

            when(comparisonStrategy.compare(oldSnapshot, newSnapshot)).thenReturn(changeTree);

            // 仍然应该追踪变更
            final ChangeSet changeSet = uow.calculateChanges();
            assertFalse(changeSet.isEmpty());
        }

        @Test
        @DisplayName("已注册为 new 的对象再注册为 clean 应被忽略")
        void registerClean_afterNew_shouldBeIgnored() {
            uow.registerNew(user1);
            uow.registerClean(user1); // 尝试再注册为 clean

            // 不应调用快照策略
            verifyNoInteractions(snapshotStrategy);

            // 仍然应该不产生变更
            final ChangeSet changeSet = uow.calculateChanges();
            assertTrue(changeSet.isEmpty());
        }

        @Test
        @DisplayName("重复注册 removed 对象应被忽略")
        void registerRemoved_duplicate_shouldBeIgnored() {
            doReturn(oldSnapshot).when(snapshotStrategy).createSnapshot(user1);

            uow.registerClean(user1);
            uow.registerRemoved(user1);
            uow.registerRemoved(user1); // 重复移除

            // 不应抛出异常，变更集应为空
            final ChangeSet changeSet = uow.calculateChanges();
            assertTrue(changeSet.isEmpty());
        }
    }

    @Nested
    @DisplayName("多对象追踪")
    class MultipleObjectTracking {

        @Test
        @DisplayName("应能同时追踪多个对象的变更")
        void shouldTrackMultipleObjects() {
            // 使用不同的 snapshotData 来区分每个快照，避免 record 的 equals() 导致 Mockito 参数匹配混乱
            final ValueNodeSnapshot oldSnapshot1 = new ValueNodeSnapshot(new PrimitiveNode("old1"));
            final ValueNodeSnapshot newSnapshot1 = new ValueNodeSnapshot(new PrimitiveNode("new1"));
            final ValueNodeSnapshot oldSnapshot2 = new ValueNodeSnapshot(new PrimitiveNode("old2"));
            final ValueNodeSnapshot newSnapshot2 = new ValueNodeSnapshot(new PrimitiveNode("new2"));

            doReturn(oldSnapshot1, newSnapshot1).when(snapshotStrategy).createSnapshot(user1);
            doReturn(oldSnapshot2, newSnapshot2).when(snapshotStrategy).createSnapshot(user2);

            uow.registerClean(user1);
            uow.registerClean(user2);

            final ChangeNode changeTree1 = new ContainerChangeNode("user1", List.of(new FieldChangeNode("name", "a", "b")));
            final ChangeNode changeTree2 = new ContainerChangeNode("user2", List.of(new FieldChangeNode("name", "c", "d")));

            when(comparisonStrategy.compare(oldSnapshot1, newSnapshot1)).thenReturn(changeTree1);
            when(comparisonStrategy.compare(oldSnapshot2, newSnapshot2)).thenReturn(changeTree2);

            final ChangeSet changeSet = uow.calculateChanges();

            assertEquals(2, changeSet.changes().size());
        }

        @Test
        @DisplayName("一个对象变更一个对象未变更时，只应生成一个变更")
        void oneChangedOneUnchanged_shouldProduceOneChange() {
            // 使用不同的 snapshotData 来区分每个快照
            final ValueNodeSnapshot snapshot1 = new ValueNodeSnapshot(new PrimitiveNode("s1"));
            final ValueNodeSnapshot newSnapshot1 = new ValueNodeSnapshot(new PrimitiveNode("ns1"));
            final ValueNodeSnapshot snapshot2 = new ValueNodeSnapshot(new PrimitiveNode("s2"));

            doReturn(snapshot1, newSnapshot1).when(snapshotStrategy).createSnapshot(user1);
            doReturn(snapshot2, snapshot2).when(snapshotStrategy).createSnapshot(user2);

            uow.registerClean(user1);
            uow.registerClean(user2);

            when(comparisonStrategy.compare(snapshot1, newSnapshot1)).thenReturn(changeTree);
            when(comparisonStrategy.compare(snapshot2, snapshot2)).thenReturn(noChangeTree);

            final ChangeSet changeSet = uow.calculateChanges();

            assertEquals(1, changeSet.changes().size());
            assertEquals(user1, changeSet.changes().get(0).target());
        }
    }

    @Nested
    @DisplayName("参数验证")
    class ParameterValidation {

        @Test
        @DisplayName("构造函数传入 null 应抛出 NullPointerException")
        void constructor_withNull_shouldThrowNPE() {
            assertThrows(NullPointerException.class, () -> new UnitOfWork(null));
        }

        @Test
        @DisplayName("registerClean 传入 null 应抛出 NullPointerException")
        void registerClean_withNull_shouldThrowNPE() {
            assertThrows(NullPointerException.class, () -> uow.registerClean(null));
        }

        @Test
        @DisplayName("registerNew 传入 null 应抛出 NullPointerException")
        void registerNew_withNull_shouldThrowNPE() {
            assertThrows(NullPointerException.class, () -> uow.registerNew(null));
        }

        @Test
        @DisplayName("registerRemoved 传入 null 应抛出 NullPointerException")
        void registerRemoved_withNull_shouldThrowNPE() {
            assertThrows(NullPointerException.class, () -> uow.registerRemoved(null));
        }
    }

    @Nested
    @DisplayName("类型安全守卫（A5）")
    class TypeSafetyTests {

        /** 与能力单元快照类型不兼容的“外来”快照。 */
        private record ForeignSnapshot(String data) implements Snapshot<String> {

            /**
             * {@inheritDoc}
             */
            @Override
            public String getSnapshotData() {
                return data;
            }
        }

        @Test
        @DisplayName("快照类型与比较策略声明不匹配时，应在比较前被类型守卫拒绝")
        void calculateChanges_incompatibleSnapshotType_shouldBeRejected() {
            // 人为构造类型不匹配：快照策略返回 ForeignSnapshot，比较策略声明仅支持 ValueNodeSnapshot
            when(mismatchedCapability.getSnapshotStrategy()).thenReturn(fakeSnapshotStrategy);
            when(mismatchedCapability.getComparisonStrategy()).thenReturn(fakeComparisonStrategy);
            when(fakeComparisonStrategy.getSupportedSnapshotType()).thenReturn(ValueNodeSnapshot.class);
            doReturn(new ForeignSnapshot("foreign")).when(fakeSnapshotStrategy).createSnapshot(user1);

            final UnitOfWork uow = new UnitOfWork(mismatchedCapability);
            uow.registerClean(user1);

            // 类型守卫应拒绝不兼容的旧快照，抛出清晰的 ClassCastException，而非静默传给比较策略
            assertThrows(ClassCastException.class, uow::calculateChanges);
            verify(fakeComparisonStrategy, never()).compare(any(), any());
        }
    }

    @Nested
    @DisplayName("并发访问特征测试（现状非线程安全，仅验证无 JVM 崩溃与死锁）")
    class ConcurrentAccessTests {

        static class TrackedEntity {
            String name = "initial";
        }

        @Test
        @DisplayName("多线程并发 registerClean 与 calculateChanges 不应导致 JVM 崩溃或死锁")
        void concurrentAccess_shouldNotCrashOrDeadlock() throws Exception {
            final DefaultTrackingCapability realCapability = new DefaultTrackingCapability(TrackingConfiguration.empty());
            final UnitOfWork uow = new UnitOfWork(realCapability);

            // 预注册一批对象，产生 calculateChanges 的比较负载
            final List<TrackedEntity> preRegistered = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                final TrackedEntity entity = new TrackedEntity();
                preRegistered.add(entity);
                uow.registerClean(entity);
            }

            final int threadCount = 8;
            final int iterationsPerThread = 200;
            final ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            final CountDownLatch ready = new CountDownLatch(threadCount);
            final CountDownLatch start = new CountDownLatch(1);
            final AtomicReference<Throwable> jvmError = new AtomicReference<>();
            final List<Future<?>> futures = new ArrayList<>();

            try {
                for (int threadIndex = 0; threadIndex < threadCount; threadIndex++) {
                    futures.add(executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        for (int i = 0; i < iterationsPerThread; i++) {
                            uow.registerClean(new TrackedEntity());
                            uow.calculateChanges();
                        }
                        return null;
                    }));
                }

                ready.await();
                start.countDown();
                for (final Future<?> future : futures) {
                    try {
                        // 带超时获取：超时即死锁信号
                        future.get(30, TimeUnit.SECONDS);
                    } catch (ExecutionException e) {
                        // JVM 级错误（StackOverflowError / OutOfMemoryError 等）才是崩溃信号；
                        // RuntimeException（如 ConcurrentModificationException）是现状非线程安全的已知特征，不在此列。
                        if (e.getCause() instanceof Error fatal) {
                            jvmError.compareAndSet(null, fatal);
                        }
                    }
                }

                assertNull(jvmError.get(), "并发访问不应导致 JVM 级错误: " + jvmError.get());
            } finally {
                executor.shutdownNow();
            }
        }
    }
}
