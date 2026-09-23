package com.nona.changeTracking.bench.result;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link BenchmarkRunId}: the identifier is kept verbatim and every path unsafe
 * shape (blank, separators, traversal, surrounding whitespace) is rejected instead of being
 * rewritten.
 */
@DisplayName("BenchmarkRunId 归档标识单元测试")
class BenchmarkRunIdUnitTest {

    @Test
    @DisplayName("版本标识应原样保留")
    void constructor_withVersionIdentifier_shouldKeepValueVerbatim() {
        final BenchmarkRunId runId = new BenchmarkRunId("1.0-SNAPSHOT");

        assertThat(runId.value()).isEqualTo("1.0-SNAPSHOT");
    }

    @Test
    @DisplayName("提交号标识应被接受")
    void constructor_withCommitIdentifier_shouldBeAccepted() {
        final BenchmarkRunId runId = new BenchmarkRunId("22767e9");

        assertThat(runId.value()).isEqualTo("22767e9");
    }

    @Test
    @DisplayName("含点与破折号的标识应被接受（相邻值：.- 不是 ..）")
    void constructor_withDotsAndDashes_shouldBeAccepted() {
        final BenchmarkRunId runId = new BenchmarkRunId("v1.2.3-4-gabc");

        assertThat(runId.value()).isEqualTo("v1.2.3-4-gabc");
    }

    @Test
    @DisplayName("相邻值 .- 应被接受，只有 .. 与单独的 . 被拒绝")
    void constructor_withAdjacentDotDash_shouldBeAccepted() {
        final BenchmarkRunId runId = new BenchmarkRunId(".-");

        assertThat(runId.value()).isEqualTo(".-");
    }

    @Test
    @DisplayName("null 标识应被拒绝")
    void constructor_withNullValue_shouldReject() {
        assertThatThrownBy(() -> new BenchmarkRunId(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("空标识应被拒绝")
    void constructor_withEmptyValue_shouldReject() {
        assertThatThrownBy(() -> new BenchmarkRunId(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("纯空白标识应被拒绝")
    void constructor_withBlankValue_shouldReject() {
        assertThatThrownBy(() -> new BenchmarkRunId("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("首尾空白应被拒绝，不得静默 trim")
    void constructor_withSurroundingWhitespace_shouldReject() {
        assertThatThrownBy(() -> new BenchmarkRunId(" 1.0 "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("含路径分隔符 / 的标识应被拒绝")
    void constructor_withForwardSlash_shouldReject() {
        assertThatThrownBy(() -> new BenchmarkRunId("1.0/2"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("含反斜杠的标识应被拒绝")
    void constructor_withBackslash_shouldReject() {
        assertThatThrownBy(() -> new BenchmarkRunId("1.0\\2"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("穿越形态 .. 应被拒绝")
    void constructor_withTraversalSequence_shouldReject() {
        assertThatThrownBy(() -> new BenchmarkRunId(".."))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BenchmarkRunId("../1.0"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BenchmarkRunId("a..b"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("单独的 . 应被拒绝")
    void constructor_withSingleDot_shouldReject() {
        assertThatThrownBy(() -> new BenchmarkRunId("."))
                .isInstanceOf(IllegalArgumentException.class);
    }
}