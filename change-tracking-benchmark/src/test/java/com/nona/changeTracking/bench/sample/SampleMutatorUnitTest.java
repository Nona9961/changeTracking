package com.nona.changeTracking.bench.sample;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SampleMutator}: property mutation, collection value
 * replacement, add / remove and reorder, plus rejected mutations.
 */
@DisplayName("SampleMutator 变更操作单元测试")
class SampleMutatorUnitTest {

    @Test
    @DisplayName("changeField 只应改变指定标量字段")
    void changeField_withScalarFieldName_shouldChangeOnlyThatField() {
        final Object sample = SampleFamily.create(SampleShape.of(20, 1, 10));
        final Map<String, Object> before = scalarSnapshot(sample);

        SampleMutator.changeField(sample, "status");

        final Map<String, Object> after = scalarSnapshot(sample);
        assertThat(after.get("status")).isNotEqualTo(before.get("status"));
        assertThat(after).containsAllEntriesOf(withoutEntry(before, "status"));
    }

    @Test
    @DisplayName("changeField 对窄样本同样生效")
    void changeField_onNarrowSample_shouldChangeField() {
        final Object sample = SampleFamily.create(SampleShape.of(5, 1, 10));
        final Object before = scalarSnapshot(sample).get("status");

        SampleMutator.changeField(sample, "status");

        assertThat(scalarSnapshot(sample).get("status")).isNotEqualTo(before);
    }

    @Test
    @DisplayName("changeAllFields 应改变每一个标量字段")
    void changeAllFields_shouldChangeEveryScalarField() {
        final Object sample = SampleFamily.create(SampleShape.of(20, 1, 10));
        final Map<String, Object> before = scalarSnapshot(sample);

        SampleMutator.changeAllFields(sample);

        final Map<String, Object> after = scalarSnapshot(sample);
        assertThat(after).hasSize(before.size());
        assertThat(after).allSatisfy((name, value) -> assertThat(value).isNotEqualTo(before.get(name)));
    }

    @Test
    @DisplayName("replaceItem 应保留元素标识并改变其内容（值替换形态）")
    void replaceItem_withValidIndex_shouldKeepIdentifierAndChangePayload() {
        final Object sample = SampleFamily.create(SampleShape.of(20, 1, 10));
        final List<?> items = itemsOf(sample);
        final Object identifierBefore = idOf(items.get(3));
        final Object skuBefore = readField(items.get(3), "sku");

        SampleMutator.replaceItem(sample, 3);

        final List<?> afterItems = itemsOf(sample);
        assertThat(afterItems).hasSize(10);
        assertThat(idOf(afterItems.get(3))).isEqualTo(identifierBefore);
        assertThat(readField(afterItems.get(3), "sku")).isNotEqualTo(skuBefore);
    }

    @Test
    @DisplayName("addItem 应追加一个标识唯一的新元素（增形态）")
    void addItem_shouldAppendIdentifiedItem() {
        final Object sample = SampleFamily.create(SampleShape.of(20, 1, 10));
        final List<Object> identifiersBefore = identifiersOf(itemsOf(sample));

        SampleMutator.addItem(sample);

        final List<?> afterItems = itemsOf(sample);
        assertThat(afterItems).hasSize(11);
        final List<Object> identifiersAfter = identifiersOf(afterItems);
        assertThat(identifiersAfter).hasSize(11);
        assertThat(identifiersAfter).containsAll(identifiersBefore);
        assertThat(identifiersAfter.stream().distinct().count()).isEqualTo(11);
    }

    @Test
    @DisplayName("addItem 对空前集合应可追加（边界）")
    void addItem_onEmptyCollection_shouldAppendOneItem() {
        final Object sample = SampleFamily.create(SampleShape.of(20, 1, 0));

        SampleMutator.addItem(sample);

        assertThat(itemsOf(sample)).hasSize(1);
    }

    @Test
    @DisplayName("removeItem 应移除指定下标的元素（删形态）")
    void removeItem_withValidIndex_shouldShrinkCollection() {
        final Object sample = SampleFamily.create(SampleShape.of(20, 1, 10));
        final Object removedIdentifier = idOf(itemsOf(sample).get(2));

        SampleMutator.removeItem(sample, 2);

        final List<?> afterItems = itemsOf(sample);
        assertThat(afterItems).hasSize(9);
        assertThat(identifiersOf(afterItems)).doesNotContain(removedIdentifier);
    }

    @Test
    @DisplayName("reorderItems 应保持元素集合不变并改变顺序（重排形态）")
    void reorderItems_shouldKeepElementsButChangeOrder() {
        final Object sample = SampleFamily.create(SampleShape.of(20, 1, 10));
        final List<Object> identifiersBefore = identifiersOf(itemsOf(sample));

        SampleMutator.reorderItems(sample);

        final List<Object> identifiersAfter = identifiersOf(itemsOf(sample));
        assertThat(identifiersAfter).containsExactlyInAnyOrderElementsOf(identifiersBefore);
        assertThat(identifiersAfter).isNotEqualTo(identifiersBefore);
    }

    @Test
    @DisplayName("reorderItems 对 0 / 1 个元素应无副作用（边界）")
    void reorderItems_withTinyCollection_shouldKeepOrder() {
        for (final int size : new int[]{0, 1}) {
            final Object sample = SampleFamily.create(SampleShape.of(20, 1, size));
            final List<Object> identifiersBefore = identifiersOf(itemsOf(sample));

            SampleMutator.reorderItems(sample);

            assertThat(identifiersOf(itemsOf(sample))).isEqualTo(identifiersBefore);
        }
    }

    @Test
    @DisplayName("changeField 对未知字段名应拒绝且不修改样本")
    void changeField_withUnknownFieldName_shouldReject() {
        final Object sample = SampleFamily.create(SampleShape.of(20, 1, 10));
        final Map<String, Object> before = scalarSnapshot(sample);

        assertThatThrownBy(() -> SampleMutator.changeField(sample, "noSuchField"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("noSuchField");
        assertThat(scalarSnapshot(sample)).isEqualTo(before);
        assertThat(itemsOf(sample)).hasSize(10);
    }

    @Test
    @DisplayName("changeField 对结构字段（address / items）应拒绝且不修改样本")
    void changeField_withStructuralFieldName_shouldReject() {
        final Object sample = SampleFamily.create(SampleShape.of(20, 1, 10));
        final Object addressBefore = readField(sample, "address");

        assertThatThrownBy(() -> SampleMutator.changeField(sample, "address"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SampleMutator.changeField(sample, "items"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(readField(sample, "address")).isSameAs(addressBefore);
        assertThat(itemsOf(sample)).hasSize(10);
    }

    @Test
    @DisplayName("changeField 字段名为 null 应拒绝")
    void changeField_withNullFieldName_shouldReject() {
        final Object sample = SampleFamily.create(SampleShape.of(20, 1, 10));

        assertThatThrownBy(() -> SampleMutator.changeField(sample, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("replaceItem 下标越界应拒绝且不修改集合")
    void replaceItem_withIndexOutOfBounds_shouldReject() {
        final Object sample = SampleFamily.create(SampleShape.of(20, 1, 10));
        final List<Object> identifiersBefore = identifiersOf(itemsOf(sample));

        assertThatThrownBy(() -> SampleMutator.replaceItem(sample, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("index");
        assertThatThrownBy(() -> SampleMutator.replaceItem(sample, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("index");
        assertThat(identifiersOf(itemsOf(sample))).isEqualTo(identifiersBefore);
    }

    @Test
    @DisplayName("removeItem 下标越界应拒绝且不修改集合")
    void removeItem_withIndexOutOfBounds_shouldReject() {
        final Object sample = SampleFamily.create(SampleShape.of(20, 1, 10));
        final List<Object> identifiersBefore = identifiersOf(itemsOf(sample));

        assertThatThrownBy(() -> SampleMutator.removeItem(sample, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("index");
        assertThatThrownBy(() -> SampleMutator.removeItem(sample, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("index");
        assertThat(identifiersOf(itemsOf(sample))).isEqualTo(identifiersBefore);
    }

    @Test
    @DisplayName("非样本类型应被拒绝且不产生副作用")
    void mutation_withUnsupportedSampleType_shouldReject() {
        final List<String> notASample = new ArrayList<>(List.of("a"));

        assertThatThrownBy(() -> SampleMutator.changeAllFields(notASample))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SampleMutator.addItem(notASample))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SampleMutator.reorderItems(notASample))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(notASample).containsExactly("a");
    }

    @Test
    @DisplayName("样本为 null 应拒绝")
    void mutation_withNullSample_shouldReject() {
        assertThatThrownBy(() -> SampleMutator.changeAllFields(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> SampleMutator.reorderItems(null))
                .isInstanceOf(NullPointerException.class);
    }

    private static Object readField(final Object target, final String fieldName) {
        try {
            final Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(target);
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to read field: " + fieldName, e);
        }
    }

    private static Map<String, Object> scalarSnapshot(final Object sample) {
        final Map<String, Object> values = new LinkedHashMap<>();
        for (final Field field : sample.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || !isScalarType(field.getType())) {
                continue;
            }
            values.put(field.getName(), readField(sample, field.getName()));
        }
        return values;
    }

    private static Map<String, Object> withoutEntry(final Map<String, Object> source, final String key) {
        final Map<String, Object> copy = new LinkedHashMap<>(source);
        copy.remove(key);
        return copy;
    }

    private static boolean isScalarType(final Class<?> type) {
        return type == String.class || type == Long.class || type == Integer.class
                || type == int.class || type == long.class || type == boolean.class;
    }

    private static List<?> itemsOf(final Object sample) {
        return (List<?>) readField(sample, "items");
    }

    private static Object idOf(final Object item) {
        return readField(item, "id");
    }

    private static List<Object> identifiersOf(final List<?> items) {
        final List<Object> identifiers = new ArrayList<>();
        for (final Object item : items) {
            identifiers.add(idOf(item));
        }
        return identifiers;
    }
}
