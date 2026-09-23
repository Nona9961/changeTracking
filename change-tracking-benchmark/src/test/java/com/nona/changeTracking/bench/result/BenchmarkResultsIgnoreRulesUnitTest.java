package com.nona.changeTracking.bench.result;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the repository ignore rules of the benchmark result archive: an archived run is
 * evidence and stays out of git, while the archive a maintainer picked by hand below
 * {@code benchmark/results/committed/} is tracked.
 * <p>
 * The direction is asserted with the real behaviour of {@code git check-ignore} against the
 * {@code .gitignore} of this repository: a matching path exits with code 0 and {@code -v} prints the
 * rule that matched, a path that is not ignored exits with code 1 and prints nothing.
 * {@code --no-index} keeps the verdict a statement about the exclude rules only, independent of what
 * is currently tracked by the index. The paths below are checked as paths, so they need not exist.
 */
@DisplayName("基准结果归档仓库忽略规则单元测试")
class BenchmarkResultsIgnoreRulesUnitTest {

    /** Repository root, the parent of the module directory surefire runs in. */
    private static final Path REPOSITORY_DIRECTORY = Path.of("").toAbsolutePath().getParent();

    /** Archive root of the benchmark module, relative to the repository root. */
    private static final String RESULTS_ROOT = "change-tracking-benchmark/benchmark/results";

    /** Directory holding the archives a maintainer picked by hand. */
    private static final String COMMITTED_DIRECTORY = RESULTS_ROOT + "/committed";

    /** An archived run directory name: the rule ignores every archive directory, whatever it is called. */
    private static final String ARCHIVE_DIRECTORY = "archived-run";

    /** The directory name of the next conflict attempt of the same archive. */
    private static final String SEQUENCED_ARCHIVE_DIRECTORY = ARCHIVE_DIRECTORY + "-2";

    /** Timeout of one {@code git check-ignore} call. */
    private static final long CHECK_IGNORE_TIMEOUT_SECONDS = 30;

    @Test
    @DisplayName("普通归档目录内的产物与目录自身应被忽略")
    void archivedArtefacts_shouldBeIgnored() {
        final ProcessResult artefact = checkIgnore(RESULTS_ROOT + "/" + ARCHIVE_DIRECTORY + "/jmh-result.json");
        final ProcessResult directory = checkIgnore(RESULTS_ROOT + "/" + ARCHIVE_DIRECTORY);

        assertThat(artefact.exitCode()).as("git check-ignore of an archived result: %s", artefact.output()).isZero();
        assertThat(artefact.output()).contains(".gitignore");
        assertThat(directory.exitCode())
                .as("git check-ignore of an archived run directory: %s", directory.output())
                .isZero();
    }

    @Test
    @DisplayName("带序号后缀的归档目录内的产物同样应被忽略（相邻形态）")
    void sequencedArchiveArtefacts_shouldBeIgnored() {
        final ProcessResult result =
                checkIgnore(RESULTS_ROOT + "/" + SEQUENCED_ARCHIVE_DIRECTORY + "/environment.json");

        assertThat(result.exitCode())
                .as("git check-ignore of a sequenced archive: %s", result.output())
                .isZero();
        assertThat(result.output()).contains(".gitignore");
    }

    @Test
    @DisplayName("committed/ 内的人工挑选归档不应被忽略")
    void committedArtefacts_shouldNotBeIgnored() {
        final ProcessResult result =
                checkIgnore(COMMITTED_DIRECTORY + "/" + ARCHIVE_DIRECTORY + "/jmh-result.json");

        assertThat(result.exitCode())
                .as("git check-ignore of a committed archive must report no ignore rule: %s", result.output())
                .isEqualTo(1);
        assertThat(result.output()).isEmpty();
    }

    @Test
    @DisplayName("committed/ 目录自身与其环境记录均不应被忽略")
    void committedDirectory_shouldNotBeIgnored() {
        // 目录模式以 / 结尾才按目录判定，故这里显式查询目录路径
        final ProcessResult directory = checkIgnore(COMMITTED_DIRECTORY + "/");
        final ProcessResult environmentRecord =
                checkIgnore(COMMITTED_DIRECTORY + "/" + ARCHIVE_DIRECTORY + "/environment.json");

        assertThat(directory.exitCode())
                .as("git check-ignore of the committed directory: %s", directory.output())
                .isEqualTo(1);
        assertThat(environmentRecord.exitCode())
                .as("git check-ignore of a committed environment record: %s", environmentRecord.output())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("相邻目录名不应被规则误伤")
    void neighbouringDirectories_shouldNotBeIgnored() {
        final ProcessResult result = checkIgnore(RESULTS_ROOT + "-old/" + ARCHIVE_DIRECTORY + "/jmh-result.json");

        assertThat(result.exitCode())
                .as("git check-ignore of a neighbouring directory: %s", result.output())
                .isEqualTo(1);
    }

    /**
     * Asks git whether the given repository relative path is ignored.
     *
     * @param relativePath path relative to the repository root, need not exist
     * @return the exit code and output of {@code git check-ignore -v --no-index}
     */
    private static ProcessResult checkIgnore(final String relativePath) {
        final List<String> command =
                List.of("git", "check-ignore", "-v", "--no-index", relativePath);
        final ProcessBuilder builder = new ProcessBuilder(new ArrayList<>(command));
        builder.directory(REPOSITORY_DIRECTORY.toFile());
        builder.redirectErrorStream(true);
        try {
            final Process process = builder.start();
            if (!process.waitFor(CHECK_IGNORE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("git check-ignore timed out for " + relativePath);
            }
            final String output =
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return new ProcessResult(process.exitValue(), output);
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to run git check-ignore for " + relativePath, e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running git check-ignore for " + relativePath, e);
        }
    }

    /**
     * Exit code and output of one {@code git check-ignore} call.
     *
     * @param exitCode the exit code, {@code 0} when the path is ignored, {@code 1} when it is not
     * @param output   the merged standard output and standard error, trimmed
     */
    private record ProcessResult(int exitCode, String output) {
    }
}