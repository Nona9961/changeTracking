package com.nona.changeTracking.bench.result;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ArchiveResultsMain}: the archive entry point treats the run identifier as
 * optional, falls back to the module relative defaults and rejects a command line whose identifier is
 * unusable as a path segment.
 */
@DisplayName("ArchiveResultsMain 归档入口单元测试")
class ArchiveResultsMainUnitTest {

    @Test
    @DisplayName("未提供 --id 时应解析为无标识并沿用模块内默认目录")
    void parse_withoutIdentifier_shouldResolveWithoutIdentifier() {
        final ArchiveResultsMain.Request request = ArchiveResultsMain.parse(new String[] {
                "--source", "custom/output"});

        assertThat(request.runId()).isEmpty();
        assertThat(request.sourceDirectory()).isEqualTo(Path.of("custom", "output"));
        assertThat(request.resultsRoot()).isEqualTo(BenchmarkResultArchiver.DEFAULT_RESULTS_ROOT);
    }

    @Test
    @DisplayName("只给标识时应使用模块内默认产物目录与归档根")
    void parse_withIdentifierOnly_shouldUseModuleRelativeDefaults() {
        final ArchiveResultsMain.Request request = ArchiveResultsMain.parse(new String[] {"--id", "1.0-SNAPSHOT"});

        assertThat(request.runId().orElseThrow().value()).isEqualTo("1.0-SNAPSHOT");
        assertThat(request.sourceDirectory()).isEqualTo(BenchmarkResultArchiver.DEFAULT_SOURCE_DIRECTORY);
        assertThat(request.resultsRoot()).isEqualTo(BenchmarkResultArchiver.DEFAULT_RESULTS_ROOT);
    }

    @Test
    @DisplayName("显式选项应覆盖默认产物目录与归档根")
    void parse_withExplicitOptions_shouldOverrideDefaults() {
        final ArchiveResultsMain.Request request = ArchiveResultsMain.parse(new String[] {
                "--id", "22767e9", "--source", "custom/output", "--root", "custom/results"});

        assertThat(request.runId().orElseThrow().value()).isEqualTo("22767e9");
        assertThat(request.sourceDirectory()).isEqualTo(Path.of("custom", "output"));
        assertThat(request.resultsRoot()).isEqualTo(Path.of("custom", "results"));
    }

    @Test
    @DisplayName("选项顺序不应影响解析结果")
    void parse_withReorderedOptions_shouldParseTheSameRequest() {
        final ArchiveResultsMain.Request request = ArchiveResultsMain.parse(new String[] {
                "--root", "custom/results", "--id", "22767e9", "--source", "custom/output"});

        assertThat(request.runId().orElseThrow().value()).isEqualTo("22767e9");
        assertThat(request.sourceDirectory()).isEqualTo(Path.of("custom", "output"));
        assertThat(request.resultsRoot()).isEqualTo(Path.of("custom", "results"));
    }

    @Test
    @DisplayName("--id 没有取值应拒绝")
    void parse_withIdentifierWithoutValue_shouldReject() {
        assertThatThrownBy(() -> ArchiveResultsMain.parse(new String[] {"--source", "out", "--id"}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("空串标识应拒绝")
    void parse_withEmptyIdentifier_shouldReject() {
        assertThatThrownBy(() -> ArchiveResultsMain.parse(new String[] {"--id", ""}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("空白标识应拒绝")
    void parse_withBlankIdentifier_shouldReject() {
        assertThatThrownBy(() -> ArchiveResultsMain.parse(new String[] {"--id", "  "}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("首尾带空白的标识应拒绝，不静默 trim")
    void parse_withSurroundingWhitespaceIdentifier_shouldReject() {
        assertThatThrownBy(() -> ArchiveResultsMain.parse(new String[] {"--id", " 1.0 "}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("含 / 的标识应拒绝")
    void parse_withForwardSlashIdentifier_shouldReject() {
        assertThatThrownBy(() -> ArchiveResultsMain.parse(new String[] {"--id", "1.0/2"}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("含反斜杠的标识应拒绝")
    void parse_withBackslashIdentifier_shouldReject() {
        assertThatThrownBy(() -> ArchiveResultsMain.parse(new String[] {"--id", "1.0\\2"}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("穿越形态标识应拒绝")
    void parse_withTraversalIdentifier_shouldReject() {
        assertThatThrownBy(() -> ArchiveResultsMain.parse(new String[] {"--id", "../1.0"}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ArchiveResultsMain.parse(new String[] {"--id", ".."}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("未知选项应拒绝")
    void parse_withUnknownOption_shouldReject() {
        assertThatThrownBy(() -> ArchiveResultsMain.parse(new String[] {"--id", "1.0", "--what", "x"}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("--source 没有取值应拒绝")
    void parse_withSourceWithoutValue_shouldReject() {
        assertThatThrownBy(() -> ArchiveResultsMain.parse(new String[] {"--id", "1.0", "--source"}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("参数数组为 null 应拒绝")
    void parse_withNullArguments_shouldReject() {
        assertThatThrownBy(() -> ArchiveResultsMain.parse(null))
                .isInstanceOf(NullPointerException.class);
    }
}