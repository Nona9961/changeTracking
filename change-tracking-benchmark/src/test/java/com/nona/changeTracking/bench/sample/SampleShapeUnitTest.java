package com.nona.changeTracking.bench.sample;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the {@link SampleShape} value object: the canonical constructor is the
 * single validation point of every dimension invariant, while {@link SampleShape#of(int, int, int)}
 * and {@link SampleShape#defaults()} are factories that delegate to it.
 */
@DisplayName("SampleShape 形状值对象单元测试")
class SampleShapeUnitTest {

    @Test
    @DisplayName("两个受支持的字段数取值应被接受，其余维度原样保留")
    void of_withSupportedFieldCounts_shouldCreateShape() {
        final SampleShape narrow = SampleShape.of(5, 2, 100);
        final SampleShape wide = SampleShape.of(20, 2, 100);

        assertThat(narrow).isEqualTo(new SampleShape(5, 2, 100));
        assertThat(wide).isEqualTo(new SampleShape(20, 2, 100));
    }

    @Test
    @DisplayName("边界取值（深度 1、集合 0、深度 6、集合 1000）应被接受")
    void of_withBoundaryValues_shouldCreateShape() {
        final SampleShape lowerBounds = SampleShape.of(20, 1, 0);
        final SampleShape beyondDocumentedRange = SampleShape.of(20, 6, 1_000);

        assertThat(lowerBounds).isEqualTo(new SampleShape(20, 1, 0));
        assertThat(beyondDocumentedRange).isEqualTo(new SampleShape(20, 6, 1_000));
    }

    @Test
    @DisplayName("defaults 应返回默认形状：字段数 20、深度 2、集合 100")
    void defaults_shouldReturnDefaultShape() {
        assertThat(SampleShape.defaults()).isEqualTo(new SampleShape(20, 2, 100));
    }

    @Test
    @DisplayName("构造器应接受维度下界（深度 1、集合 0）并原样保留")
    void constructor_withLowerBoundDimensions_shouldCreateShape() {
        final SampleShape lowerBounds = new SampleShape(20, 1, 0);

        assertThat(lowerBounds.nestingDepth()).isEqualTo(1);
        assertThat(lowerBounds.collectionSize()).isZero();
    }

    @Test
    @DisplayName("不支持的字段数（4 / 6 / 21）应在构造时被拒绝")
    void constructor_withUnsupportedFieldCount_shouldReject() {
        assertThatThrownBy(() -> new SampleShape(4, 1, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fieldCount");
        assertThatThrownBy(() -> new SampleShape(6, 1, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fieldCount");
        assertThatThrownBy(() -> new SampleShape(21, 1, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fieldCount");
    }

    @Test
    @DisplayName("字段数 0 与负数应在构造时被拒绝")
    void constructor_withNonPositiveFieldCount_shouldReject() {
        assertThatThrownBy(() -> new SampleShape(0, 1, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fieldCount");
        assertThatThrownBy(() -> new SampleShape(-1, 1, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fieldCount");
    }

    @Test
    @DisplayName("嵌套深度 0 与负数应在构造时被拒绝")
    void constructor_withNonPositiveNestingDepth_shouldReject() {
        assertThatThrownBy(() -> new SampleShape(20, 0, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nestingDepth");
        assertThatThrownBy(() -> new SampleShape(20, -1, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nestingDepth");
    }

    @Test
    @DisplayName("集合规模负数应在构造时被拒绝")
    void constructor_withNegativeCollectionSize_shouldReject() {
        assertThatThrownBy(() -> new SampleShape(20, 1, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("collectionSize");
    }

    @Test
    @DisplayName("多个维度同时非法时应报告首个受校验的维度 fieldCount")
    void constructor_withSeveralInvalidDimensions_shouldReportFirstValidatedDimension() {
        assertThatThrownBy(() -> new SampleShape(6, 0, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fieldCount");
    }
}
