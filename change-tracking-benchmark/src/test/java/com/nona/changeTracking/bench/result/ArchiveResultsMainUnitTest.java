package com.nona.changeTracking.bench.result;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ArchiveResultsMain}: the archive entry point treats the run identifier as
 * optional, resolves an omitted {@code --source} and an omitted {@code --root} against the benchmark
 * module directory (never against the current working directory), keeps an explicitly given path
 * verbatim and rejects a command line whose identifier is unusable as a path segment.
 * <p>
 * The omitted options are asserted against an injected module directory, so each test states its own
 * precondition instead of depending on where the test jvm was started; the wiring to the real module
 * directory is covered by the one test that calls the production overload without a module directory,
 * whose expected value is the module directory of this module (the working directory of surefire, the
 * established convention of this module's tests).
 */
@DisplayName("ArchiveResultsMain 归档入口单元测试")
class ArchiveResultsMainUnitTest {

    /** Module directory of this module, the working directory of surefire. */
    private static final Path MODULE_DIRECTORY = Path.of("").toAbsolutePath();

    /** Instant of the archival used by the tests that archive the defaulted request. */
    private static final Instant TIMESTAMP = Instant.parse("2026-09-22T15:05:01Z");

    @Test
    @DisplayName("未提供 --id 时应解析为无标识并沿用模块内默认归档根")
    void parse_withoutIdentifier_shouldResolveWithoutIdentifier(@TempDir final Path moduleDirectory) {
        final ArchiveResultsMain.Request request = ArchiveResultsMain.parse(new String[] {
                "--source", "custom/output"}, () -> moduleDirectory);

        assertThat(request.runId()).isEmpty();
        assertThat(request.sourceDirectory()).isEqualTo(Path.of("custom", "output"));
        assertThat(request.resultsRoot()).isEqualTo(moduleDirectory.resolve("benchmark").resolve("results"));
    }

    @Test
    @DisplayName("只给标识时应使用模块内默认产物目录与归档根")
    void parse_withIdentifierOnly_shouldUseModuleRelativeDefaults(@TempDir final Path moduleDirectory) {
        final ArchiveResultsMain.Request request =
                ArchiveResultsMain.parse(new String[] {"--id", "1.0-SNAPSHOT"}, () -> moduleDirectory);

        assertThat(request.runId().orElseThrow().value()).isEqualTo("1.0-SNAPSHOT");
        assertThat(request.sourceDirectory()).isEqualTo(moduleDirectory.resolve("target").resolve("benchmark-results"));
        assertThat(request.resultsRoot()).isEqualTo(moduleDirectory.resolve("benchmark").resolve("results"));
        assertThat(request.sourceDirectory().isAbsolute())
                .as("the defaulted source directory is resolved, never left relative to the working directory")
                .isTrue();
        assertThat(request.resultsRoot().isAbsolute())
                .as("the defaulted archive root is resolved, never left relative to the working directory")
                .isTrue();
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
    @DisplayName("缺省归档根应与归档器的模块目录解析规则一致")
    void parse_defaultResultsRoot_shouldFollowTheArchiverResolution(@TempDir final Path moduleDirectory) {
        final ArchiveResultsMain.Request request =
                ArchiveResultsMain.parse(new String[] {"--id", "1.0-SNAPSHOT"}, () -> moduleDirectory);

        assertThat(request.resultsRoot()).isEqualTo(BenchmarkResultArchiver.resolveResultsRoot(moduleDirectory));
    }

    @Test
    @DisplayName("只显式给 --source 时缺省归档根仍按模块目录解析")
    void parse_withExplicitSourceOnly_shouldResolveTheDefaultRootAgainstTheModuleDirectory(
            @TempDir final Path moduleDirectory) {
        final ArchiveResultsMain.Request request = ArchiveResultsMain.parse(new String[] {
                "--source", "custom/output"}, () -> moduleDirectory);

        assertThat(request.sourceDirectory()).isEqualTo(Path.of("custom", "output"));
        assertThat(request.resultsRoot()).isEqualTo(moduleDirectory.resolve("benchmark").resolve("results"));
    }

    @Test
    @DisplayName("只显式给 --root 时缺省产物目录仍按模块目录解析")
    void parse_withExplicitRootOnly_shouldResolveTheDefaultSourceAgainstTheModuleDirectory(
            @TempDir final Path moduleDirectory) {
        final ArchiveResultsMain.Request request = ArchiveResultsMain.parse(new String[] {
                "--root", "custom/results"}, () -> moduleDirectory);

        assertThat(request.resultsRoot()).isEqualTo(Path.of("custom", "results"));
        assertThat(request.sourceDirectory()).isEqualTo(moduleDirectory.resolve("target").resolve("benchmark-results"));
    }

    @Test
    @DisplayName("显式给足两项时不应咨询模块目录")
    void parse_withBothOptionsExplicit_shouldNotConsultTheModuleDirectory() {
        final ArchiveResultsMain.Request request = ArchiveResultsMain.parse(new String[] {
                "--id", "22767e9", "--source", "custom/output", "--root", "custom/results"},
                () -> {
                    throw new IllegalStateException("the module directory must not be consulted");
                });

        assertThat(request.sourceDirectory()).isEqualTo(Path.of("custom", "output"));
        assertThat(request.resultsRoot()).isEqualTo(Path.of("custom", "results"));
    }

    @Test
    @DisplayName("显式绝对路径应原样保留，不被模块目录改写")
    void parse_withExplicitAbsolutePaths_shouldKeepThemVerbatim(@TempDir final Path directory) {
        final Path sourceDirectory = directory.resolve("source");
        final Path resultsRoot = directory.resolve("results");

        final ArchiveResultsMain.Request request = ArchiveResultsMain.parse(new String[] {
                "--source", sourceDirectory.toString(), "--root", resultsRoot.toString()},
                () -> directory.resolve("other-module"));

        assertThat(request.sourceDirectory()).isEqualTo(sourceDirectory);
        assertThat(request.resultsRoot()).isEqualTo(resultsRoot);
    }

    @Test
    @DisplayName("未注入模块目录时缺省值应按本模块目录解析")
    void parse_withoutModuleDirectoryArgument_shouldUseTheRealModuleDirectory() {
        final ArchiveResultsMain.Request request = ArchiveResultsMain.parse(new String[] {"--id", "1.0-SNAPSHOT"});

        assertThat(request.sourceDirectory()).isEqualTo(MODULE_DIRECTORY.resolve("target").resolve("benchmark-results"));
        assertThat(request.resultsRoot()).isEqualTo(MODULE_DIRECTORY.resolve("benchmark").resolve("results"));
    }

    @Test
    @DisplayName("模块目录来源为 null 应拒绝")
    void parse_withNullModuleDirectory_shouldReject() {
        assertThatThrownBy(() -> ArchiveResultsMain.parse(new String[] {"--id", "1.0-SNAPSHOT"}, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("缺省产物目录缺失时应以模块内绝对路径报缺失，且不创建归档根")
    void archive_withDefaultedOptions_shouldReportTheMissingModuleRelativeSource(@TempDir final Path moduleDirectory) {
        final ArchiveResultsMain.Request request = ArchiveResultsMain.parse(new String[0], () -> moduleDirectory);

        assertThatThrownBy(() -> BenchmarkResultArchiver.archive(
                request.runId(), request.sourceDirectory(), request.resultsRoot(), TIMESTAMP))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(moduleDirectory.resolve("target").resolve("benchmark-results").toString());
        assertThat(Files.notExists(request.resultsRoot()))
                .as("a rejected archive request must not create the defaulted archive root")
                .isTrue();
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
