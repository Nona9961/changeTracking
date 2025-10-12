package com.nona.changeTracking.domain.detector;

import com.nona.changeTracking.domain.model.changeset.FieldChange;
import com.nona.changeTracking.domain.model.changeset.ObjectChange;
import com.nona.changeTracking.domain.model.unitofwork.MapSnapshot;
import com.nona.changeTracking.domain.model.unitofwork.ObjectSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 这个测试类负责验证 {@link ChangeDetector} 的协调与分发逻辑，
 * 以及 {@link ChangeDetectorBuilder} 的构建和配置能力。
 * <p>
 * 它不关心具体的比较算法（这由 {@code MapSnapshotComparatorTest} 负责），
 * 而是确保正确的 {@link SnapshotComparator} 被查找和调用，以及
 * 边界情况（如不支持的快照类型）被正确处理。
 */
@DisplayName("ChangeDetector & Builder 集成测试")
class ChangeDetectorTest {

    @Test
    @DisplayName("Builder.withDefaults() 应能通过 ServiceLoader 加载默认的 MapSnapshotComparator")
    void builderWithDefaults_shouldLoadDefaultComparator() {
        // --- Arrange ---
        // 使用 withDefaults() 创建一个应该包含默认比较器的 detector
        final ChangeDetector detector = ChangeDetectorBuilder.create().withDefaults().build();
        final MapSnapshot mapSnapshot = new MapSnapshot(Map.of("field", "value"));
        final var testObject = new Object() { public String field = "value"; };

        // --- Act & Assert ---
        // 验证它能成功处理 MapSnapshot 而不抛出异常。
        // 这间接证明了 MapSnapshotComparator 已被正确加载。
        assertDoesNotThrow(() -> detector.detectChanges(testObject, mapSnapshot));
    }

    @Test
    @DisplayName("手动注册的 Comparator 应能覆盖默认加载的 Comparator")
    void builderWithComparator_shouldOverrideDefaultLoadedComparator() {
        // --- Arrange ---
        // 创建一个自定义的 Comparator，它总是返回一个特定的变更
        final SnapshotComparator<MapSnapshot> customMapComparator = new SnapshotComparator<>() {
            @Override
            public Class<MapSnapshot> getSupportedSnapshotType() {
                return MapSnapshot.class;
            }

            @Override
            public List<FieldChange> compare(MapSnapshot snapshot, Object currentObject) {
                return List.of(new FieldChange("overridden", "old", "new"));
            }
        };

        // 使用 withDefaults() 加载默认实现，然后用 withComparator() 覆盖它
        final ChangeDetector detector = ChangeDetectorBuilder.create()
                .withDefaults()
                .withComparator(MapSnapshot.class, customMapComparator)
                .build();

        final MapSnapshot mapSnapshot = new MapSnapshot(Map.of());

        // --- Act ---
        final Optional<ObjectChange> result = detector.detectChanges(new Object(), mapSnapshot);

        // --- Assert ---
        // 验证结果是由我们自定义的 comparator 生成的，而不是默认的
        assertTrue(result.isPresent(), "应该检测到变更");
        final ObjectChange objectChange = result.get();
        assertEquals(1, objectChange.fieldChanges().size());
        assertEquals("overridden", objectChange.fieldChanges().get(0).fieldName());
    }

    @Test
    @DisplayName("Detector 面对一个合法但未注册的 Snapshot 类型时应抛出异常")
    void detector_shouldThrowExceptionForUnregisteredButValidSnapshotType() {
        // --- Arrange ---
        // 使用 withDefaults() 创建一个只支持 MapSnapshot 的 detector
        final ChangeDetector detector = ChangeDetectorBuilder.create().withDefaults().build();

        // ObjectSnapshot 是一个在 sealed 接口中合法的 Snapshot 类型，
        // 但我们的默认配置没有为它注册 Comparator。
        final ObjectSnapshot objectSnapshot = new ObjectSnapshot(new Object());

        // --- Act & Assert ---
        // 验证 detector 在遇到 ObjectSnapshot 时会抛出预期的异常
        final var exception = assertThrows(
                UnsupportedSnapshotTypeException.class,
                () -> detector.detectChanges(new Object(), objectSnapshot)
        );

        // 验证异常信息是否包含了正确的类型名称
        assertTrue(exception.getMessage().contains(ObjectSnapshot.class.getName()));
    }

    @Test
    @DisplayName("完全手动配置的 Detector 应能正常工作")
    void detector_withManualConfiguration_shouldWorkCorrectly() {
        // --- Arrange ---
        // --- 修正点 ---
        // 将 Lambda 表达式替换为完整的匿名内部类实现
        final SnapshotComparator<MapSnapshot> manualComparator = new SnapshotComparator<>() {
            @Override
            public Class<MapSnapshot> getSupportedSnapshotType() {
                return MapSnapshot.class;
            }

            @Override
            public List<FieldChange> compare(MapSnapshot snapshot, Object currentObject) {
                return List.of(); // 对于此测试，返回空列表即可
            }
        };

        // 创建一个不使用 withDefaults()，完全手动配置的 detector
        final ChangeDetector detector = ChangeDetectorBuilder.create()
                .withComparator(MapSnapshot.class, manualComparator)
                .build();

        final MapSnapshot mapSnapshot = new MapSnapshot(Map.of());

        // --- Act & Assert ---
        // 验证手动注册的 comparator 被正确调用
        assertDoesNotThrow(() -> detector.detectChanges(new Object(), mapSnapshot));

        // 验证它不能处理任何其他类型
        final ObjectSnapshot objectSnapshot = new ObjectSnapshot(new Object());
        assertThrows(UnsupportedSnapshotTypeException.class,
                () -> detector.detectChanges(new Object(), objectSnapshot)
        );
    }
}
