package com.nona.changeTracking.bench.sample;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SampleFamily}: shape dispatch, scalar field filling,
 * nesting chain, collection construction and identifier extraction.
 */
@DisplayName("SampleFamily 样本族构造单元测试")
class SampleFamilyUnitTest {

    @Test
    @DisplayName("20 字段形状应产出宽样本，且全部标量字段非空")
    void create_withWideFieldCount_shouldFillEveryScalarField() {
        final Object sample = SampleFamily.create(SampleShape.of(20, 1, 10));

        assertThat(sample).isInstanceOf(SampleOrder.class);
        assertThat(scalarValues(sample)).hasSize(20).allSatisfy(value -> assertThat(value).isNotNull());
        assertThat(itemsOf(sample)).hasSize(10);
        assertThat(readField(sample, "address")).isNotNull();
    }

    @Test
    @DisplayName("5 字段形状应产出窄样本，且全部标量字段非空")
    void create_withNarrowFieldCount_shouldFillEveryScalarField() {
        final Object sample = SampleFamily.create(SampleShape.of(5, 1, 10));

        assertThat(sample).isInstanceOf(SampleOrderSummary.class);
        assertThat(scalarValues(sample)).hasSize(5).allSatisfy(value -> assertThat(value).isNotNull());
        assertThat(itemsOf(sample)).hasSize(10);
        assertThat(readField(sample, "address")).isNotNull();
    }

    @Test
    @DisplayName("深度 1 的嵌套链应终止于叶子节点")
    void create_withDepthOne_shouldStopAtLeaf() {
        final Object sample = SampleFamily.create(SampleShape.of(20, 1, 0));
        final List<Object> chain = addressChain(sample);

        assertThat(chain).hasSize(1);
        assertThat(chain.get(0)).isInstanceOf(SampleAddressLeaf.class);
    }

    @Test
    @DisplayName("深度 3 的嵌套链应为 Link → Link → Leaf")
    void create_withDepthThree_shouldBuildLinkChain() {
        final Object sample = SampleFamily.create(SampleShape.of(20, 3, 0));
        final List<Object> chain = addressChain(sample);

        assertThat(chain).hasSize(3);
        assertThat(chain.get(0)).isInstanceOf(SampleAddressLink.class);
        assertThat(chain.get(1)).isInstanceOf(SampleAddressLink.class);
        assertThat(chain.get(2)).isInstanceOf(SampleAddressLeaf.class);
        assertThat(((SampleAddressLink) chain.get(1)).next()).isSameAs(chain.get(2));
    }

    @Test
    @DisplayName("深度 6（超出需求档位）仍应构建完整链，不硬编码上限 5")
    void create_withDepthBeyondDocumentedRange_shouldStillBuildChain() {
        final Object sample = SampleFamily.create(SampleShape.of(20, 6, 0));

        assertThat(addressChain(sample)).hasSize(6);
    }

    @Test
    @DisplayName("集合元素应有唯一且连续的 id，sku 非空")
    void create_withCollectionSizes_shouldCreateIdentifiedItems() {
        for (final int size : new int[]{10, 100, 1_000}) {
            final Object sample = SampleFamily.create(SampleShape.of(20, 1, size));
            final List<?> items = itemsOf(sample);
            final Set<Object> identifiers = new HashSet<>();

            assertThat(items).hasSize(size);
            for (final Object item : items) {
                assertThat(idOf(item)).isNotNull();
                assertThat(readField(item, "sku")).isNotNull();
                identifiers.add(idOf(item));
            }
            assertThat(identifiers).hasSize(size);
        }
    }

    @Test
    @DisplayName("集合规模 0 应产出显式空集合而非 null")
    void create_withZeroCollectionSize_shouldExposeEmptyCollection() {
        final Object sample = SampleFamily.create(SampleShape.of(20, 1, 0));

        assertThat(itemsOf(sample)).isEmpty();
        assertThat(itemsOf(sample)).isNotNull();
    }

    @Test
    @DisplayName("两次 create 应返回互不共享可变集合的独立样本")
    void create_calledTwice_shouldReturnIndependentSamples() {
        final SampleShape shape = SampleShape.of(20, 2, 10);
        final Object first = SampleFamily.create(shape);
        final Object second = SampleFamily.create(shape);

        assertThat(first).isNotSameAs(second);
        assertThat(itemsOf(first)).isNotSameAs(itemsOf(second));
        assertThat(scalarValues(second)).isEqualTo(scalarValues(first));
    }

    @Test
    @DisplayName("样本访问器应暴露集合、嵌套链与元素标识")
    void accessors_shouldExposeSampleStructure() {
        final SampleOrder wide = (SampleOrder) SampleFamily.create(SampleShape.of(20, 2, 3));
        final SampleOrderSummary narrow = (SampleOrderSummary) SampleFamily.create(SampleShape.of(5, 1, 2));

        assertThat(wide.items()).hasSize(3);
        assertThat(wide.address()).isInstanceOf(SampleAddressLink.class);
        final SampleLineItem first = wide.items().get(0);
        assertThat(first.id()).isNotNull();
        assertThat(first.sku()).isNotBlank();
        assertThat(first.quantity()).isPositive();
        assertThat(first.unitPriceCents()).isPositive();
        assertThat(((SampleAddressLink) wide.address()).city()).isNotBlank();
        assertThat(((SampleAddressLink) wide.address()).street()).isNotBlank();
        assertThat(narrow.items()).hasSize(2);
        assertThat(narrow.address()).isInstanceOf(SampleAddressLeaf.class);
        assertThat(((SampleAddressLeaf) narrow.address()).city()).isNotBlank();
    }

    @Test
    @DisplayName("标识提取器应含集合元素类型并按元素 id 提取")
    void identifierExtractors_shouldResolveItemIdentifier() {
        final Map<Class<?>, Function<Object, Object>> extractors = SampleFamily.identifierExtractors();
        final Object sample = SampleFamily.create(SampleShape.of(20, 1, 10));
        final List<?> items = itemsOf(sample);

        assertThat(extractors).containsKey(SampleLineItem.class);
        final Function<Object, Object> extractor = extractors.get(SampleLineItem.class);
        assertThat(extractor.apply(items.get(0))).isEqualTo(idOf(items.get(0)));
        assertThat(extractor.apply(items.get(3))).isEqualTo(idOf(items.get(3)));
    }

    @Test
    @DisplayName("shape 为 null 应被拒绝")
    void create_withNullShape_shouldReject() {
        assertThatThrownBy(() -> SampleFamily.create(null))
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

    private static List<Object> scalarValues(final Object sample) {
        final List<Object> values = new ArrayList<>();
        for (final Field field : sample.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            if (isScalarType(field.getType())) {
                values.add(readField(sample, field.getName()));
            }
        }
        return values;
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

    private static List<Object> addressChain(final Object sample) {
        final List<Object> chain = new ArrayList<>();
        Object current = readField(sample, "address");
        while (current != null) {
            chain.add(current);
            if (current instanceof SampleAddressLink link) {
                current = link.next();
            } else {
                current = null;
            }
        }
        return chain;
    }
}
