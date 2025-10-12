package com.nona.changeTracking.internal.detector;

import com.nona.changeTracking.domain.detector.SnapshotComparator;
import com.nona.changeTracking.domain.model.changeset.FieldChange;
import com.nona.changeTracking.domain.model.unitofwork.MapSnapshot;
import com.nona.changeTracking.internal.util.ReflectionUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class MapSnapshotComparator implements SnapshotComparator<MapSnapshot> {

    @Override
    public Class<MapSnapshot> getSupportedSnapshotType() {
        return MapSnapshot.class;
    }

    @Override
    public List<FieldChange> compare(final MapSnapshot snapshot, final Object currentObject) {
        final List<FieldChange> changes = new ArrayList<>();
        final Map<String, Object> snapshotMap = snapshot.getSnapshotData();

        final List<Field> allObjectFields = ReflectionUtils.getAllFields(currentObject.getClass());

        // 1. 获取当前对象的所有有效字段，存入 Map 以便快速查找
        final Map<String, Field> currentValidFields = allObjectFields.stream()
                .filter(field -> !Modifier.isStatic(field.getModifiers()) && !Modifier.isTransient(field.getModifiers()))
                .peek(field -> field.setAccessible(true))
                .collect(Collectors.toMap(Field::getName, Function.identity()));

        // 2. 获取所有需要被忽略的字段名称集合
        final Set<String> ignoredFieldNames = allObjectFields.stream()
                .filter(field -> Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers()))
                .map(Field::getName)
                .collect(Collectors.toSet());

        // 3. 只遍历快照中存在的字段
        for (final Map.Entry<String, Object> snapshotEntry : snapshotMap.entrySet()) {
            final String fieldName = snapshotEntry.getKey();

            // **核心修正点**: 如果字段名在忽略列表中，则直接跳过
            if (ignoredFieldNames.contains(fieldName)) {
                continue;
            }

            final Object oldValue = snapshotEntry.getValue();
            final Field field = currentValidFields.get(fieldName);

            if (field == null) {
                // 当前对象中不存在这个（非static/transient）字段了，视为变更
                changes.add(new FieldChange(fieldName, oldValue, null));
                continue;
            }

            try {
                final Object newValue = field.get(currentObject);
                if (!Objects.equals(oldValue, newValue)) {
                    changes.add(new FieldChange(fieldName, oldValue, newValue));
                }
            } catch (final IllegalAccessException e) {
                throw new IllegalStateException("Failed to access field: " + fieldName, e);
            }
        }
        return changes;
    }
}
