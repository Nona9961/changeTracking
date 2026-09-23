package com.nona.changeTracking.bench.result;

import com.nona.changeTracking.bench.env.EnvironmentRecordWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link BenchmarkResultArchiver}: both run artefacts land in one archive directory
 * named {@code <runId>-<utc timestamp>} (or the timestamp alone when the caller supplied no
 * identifier), a name that is already taken gains a sequence number instead of failing, and no
 * existing archive is ever overwritten, renamed or deleted.
 * <p>
 * The instant below is the input of the naming rule, never an assertion about the current time: the
 * expected directory name is derived from that instant (shape of the timestamp, equality with the
 * name derived by {@link ArchiveDirectoryName} from the same inputs).
 */
@DisplayName("BenchmarkResultArchiver 结果归档单元测试")
class BenchmarkResultArchiverUnitTest {

    /** Result content written into the archive source directory. */
    private static final String RESULT_CONTENT = "[{\"benchmark\":\"com.nona.bench.Example\"}]";

    /** Environment record content written into the archive source directory. */
    private static final String ENVIRONMENT_CONTENT = "{\"jdkVersion\":\"25.0.4\"}";

    /** Instant of the archival used by these tests. */
    private static final Instant TIMESTAMP = Instant.parse("2026-09-22T15:05:01Z");

    /** Shape of a directory name carrying the identifier: identifier, dash, UTC second timestamp. */
    private static final String NAMED_PATTERN = "1\\.0-SNAPSHOT-\\d{8}T\\d{6}Z";

    /** Shape of a directory name carrying no identifier: the UTC second timestamp alone. */
    private static final String TIMESTAMP_ONLY_PATTERN = "\\d{8}T\\d{6}Z";

    @Test
    @DisplayName("归档应把两份产物复制到 <root>/<标识>-<时间戳> 并返回该目录")
    void archive_shouldCopyBothArtefacts(@TempDir final Path directory) throws IOException {
        final Path source = sourceDirectory(directory.resolve("source"));
        final Path root = directory.resolve("results");

        final Path archiveDirectory = BenchmarkResultArchiver.archive(Optional.of(runId()), source, root, TIMESTAMP);

        assertThat(archiveDirectory.getParent()).isEqualTo(root);
        assertThat(archiveDirectory.getFileName().toString()).matches(NAMED_PATTERN);
        assertThat(archiveDirectory)
                .isEqualTo(root.resolve(ArchiveDirectoryName.of(Optional.of(runId()), TIMESTAMP).name()));
        assertThat(Files.readString(archiveDirectory.resolve(JmhResultJsonParser.RESULT_FILE_NAME)))
                .isEqualTo(RESULT_CONTENT);
        assertThat(Files.readString(archiveDirectory.resolve(EnvironmentRecordWriter.FILE_NAME)))
                .isEqualTo(ENVIRONMENT_CONTENT);
    }

    @Test
    @DisplayName("未提供标识时目录名应只有 UTC 时间戳")
    void archive_withoutIdentifier_shouldNameTheDirectoryAfterTheTimestamp(@TempDir final Path directory)
            throws IOException {
        final Path source = sourceDirectory(directory.resolve("source"));
        final Path root = directory.resolve("results");

        final Path archiveDirectory = BenchmarkResultArchiver.archive(Optional.empty(), source, root, TIMESTAMP);

        assertThat(archiveDirectory.getFileName().toString()).matches(TIMESTAMP_ONLY_PATTERN);
        assertThat(archiveDirectory).isEqualTo(root.resolve(ArchiveDirectoryName.timestampOnly(TIMESTAMP).name()));
        assertThat(Files.readString(archiveDirectory.resolve(JmhResultJsonParser.RESULT_FILE_NAME)))
                .isEqualTo(RESULT_CONTENT);
    }

    @Test
    @DisplayName("归档根不存在时应逐级创建")
    void archive_shouldCreateMissingRoot(@TempDir final Path directory) throws IOException {
        final Path source = sourceDirectory(directory.resolve("source"));
        final Path root = directory.resolve("nested").resolve("benchmark").resolve("results");

        final Path archiveDirectory = BenchmarkResultArchiver.archive(Optional.of(runId()), source, root, TIMESTAMP);

        assertThat(archiveDirectory.getFileName().toString()).matches(NAMED_PATTERN);
        assertThat(Files.isDirectory(archiveDirectory)).isTrue();
    }

    @Test
    @DisplayName("归档目录内应恰好是两份产物，无暂存残留")
    void archive_shouldLeaveExactlyTwoArtefacts(@TempDir final Path directory) throws IOException {
        final Path source = sourceDirectory(directory.resolve("source"));
        final Path root = directory.resolve("results");

        final Path archiveDirectory = BenchmarkResultArchiver.archive(Optional.of(runId()), source, root, TIMESTAMP);

        assertThat(entriesOf(archiveDirectory))
                .containsExactlyInAnyOrder(JmhResultJsonParser.RESULT_FILE_NAME, EnvironmentRecordWriter.FILE_NAME);
    }

    @Test
    @DisplayName("目录名冲突时应追加序号 -2，既有归档内容与目录名均不变")
    void archive_withTakenName_shouldAppendTheFirstSequenceNumber(@TempDir final Path directory) throws IOException {
        final Path source = sourceDirectory(directory.resolve("source"));
        final Path root = Files.createDirectories(directory.resolve("results"));
        final ArchiveDirectoryName baseName = ArchiveDirectoryName.of(Optional.of(runId()), TIMESTAMP);
        final Path existingArchive = Files.createDirectories(root.resolve(baseName.name()));
        final Path existingResult = existingArchive.resolve(JmhResultJsonParser.RESULT_FILE_NAME);
        Files.writeString(existingResult, "previous run");

        final Path archiveDirectory = BenchmarkResultArchiver.archive(Optional.of(runId()), source, root, TIMESTAMP);

        assertThat(archiveDirectory.getFileName().toString()).isEqualTo(baseName.withSequence(2).name());
        assertThat(Files.readString(existingResult)).isEqualTo("previous run");
        assertThat(entriesOf(existingArchive)).containsExactly(JmhResultJsonParser.RESULT_FILE_NAME);
        assertThat(entriesOf(root)).containsExactlyInAnyOrder(baseName.name(), baseName.withSequence(2).name());
    }

    @Test
    @DisplayName("连续两次冲突应落到 -3（相邻序号）")
    void archive_withTwoTakenNames_shouldAppendTheNextSequenceNumber(@TempDir final Path directory)
            throws IOException {
        final Path source = sourceDirectory(directory.resolve("source"));
        final Path root = Files.createDirectories(directory.resolve("results"));
        final ArchiveDirectoryName baseName = ArchiveDirectoryName.of(Optional.of(runId()), TIMESTAMP);
        Files.createDirectories(root.resolve(baseName.name()));
        Files.createDirectories(root.resolve(baseName.withSequence(2).name()));

        final Path archiveDirectory = BenchmarkResultArchiver.archive(Optional.of(runId()), source, root, TIMESTAMP);

        assertThat(archiveDirectory.getFileName().toString()).isEqualTo(baseName.withSequence(3).name());
        assertThat(entriesOf(root))
                .containsExactlyInAnyOrder(baseName.name(), baseName.withSequence(2).name(),
                        baseName.withSequence(3).name());
    }

    @Test
    @DisplayName("同名条目是普通文件时也视为已占用，文件不被覆盖或删除")
    void archive_withTakenNameBeingARegularFile_shouldAppendTheNextSequenceNumber(@TempDir final Path directory)
            throws IOException {
        final Path source = sourceDirectory(directory.resolve("source"));
        final Path root = Files.createDirectories(directory.resolve("results"));
        final ArchiveDirectoryName baseName = ArchiveDirectoryName.of(Optional.of(runId()), TIMESTAMP);
        final Path blockingFile = root.resolve(baseName.name());
        Files.writeString(blockingFile, "not a directory");

        final Path archiveDirectory = BenchmarkResultArchiver.archive(Optional.of(runId()), source, root, TIMESTAMP);

        assertThat(archiveDirectory.getFileName().toString()).isEqualTo(baseName.withSequence(2).name());
        assertThat(Files.readString(blockingFile)).isEqualTo("not a directory");
        assertThat(entriesOf(root))
                .containsExactlyInAnyOrder(baseName.name(), baseName.withSequence(2).name());
    }

    @Test
    @DisplayName("全部候选名被占用时应报错，且不创建任何新归档条目")
    void archive_withEveryCandidateNameTaken_shouldReject(@TempDir final Path directory) throws IOException {
        final Path source = sourceDirectory(directory.resolve("source"));
        final Path root = Files.createDirectories(directory.resolve("results"));
        final ArchiveDirectoryName baseName = ArchiveDirectoryName.of(Optional.of(runId()), TIMESTAMP);
        Files.createDirectories(root.resolve(baseName.name()));
        for (int sequence = 2; sequence <= BenchmarkResultArchiver.MAX_CONFLICT_ATTEMPTS; sequence++) {
            Files.createDirectories(root.resolve(baseName.withSequence(sequence).name()));
        }
        final int occupiedEntries = entriesOf(root).size();

        assertThatThrownBy(() -> BenchmarkResultArchiver.archive(Optional.of(runId()), source, root, TIMESTAMP))
                .isInstanceOf(IllegalStateException.class);

        assertThat(entriesOf(root)).hasSize(occupiedEntries);
    }

    @Test
    @DisplayName("空闲目录名应被原子创建并返回")
    void resolveArchiveDirectory_withFreeName_shouldCreateAndReturnIt(@TempDir final Path directory)
            throws IOException {
        final Path root = Files.createDirectories(directory.resolve("results"));
        final ArchiveDirectoryName baseName = ArchiveDirectoryName.of(Optional.of(runId()), TIMESTAMP);

        final Path resolved = BenchmarkResultArchiver.resolveArchiveDirectory(root, baseName);

        assertThat(resolved).isEqualTo(root.resolve(baseName.name()));
        assertThat(Files.isDirectory(resolved)).isTrue();
    }

    @Test
    @DisplayName("归档根缺失时解析目录名应报告写入失败")
    void resolveArchiveDirectory_withoutRoot_shouldReportWriteFailure(@TempDir final Path directory) {
        final Path missingRoot = directory.resolve("missing-root");
        final ArchiveDirectoryName baseName = ArchiveDirectoryName.of(Optional.of(runId()), TIMESTAMP);

        assertThatThrownBy(() -> BenchmarkResultArchiver.resolveArchiveDirectory(missingRoot, baseName))
                .isInstanceOf(UncheckedIOException.class);

        assertThat(Files.notExists(missingRoot)).isTrue();
    }

    @Test
    @DisplayName("源目录不存在时应拒绝且不创建归档根")
    void archive_withMissingSourceDirectory_shouldReject(@TempDir final Path directory) {
        final Path source = directory.resolve("missing-source");
        final Path root = directory.resolve("results");

        assertThatThrownBy(() -> BenchmarkResultArchiver.archive(Optional.of(runId()), source, root, TIMESTAMP))
                .isInstanceOf(IllegalStateException.class);

        assertThat(Files.notExists(root)).isTrue();
    }

    @Test
    @DisplayName("源目录缺少结果 JSON 时应拒绝且不创建归档根")
    void archive_withoutResultFile_shouldReject(@TempDir final Path directory) throws IOException {
        final Path source = sourceDirectory(directory.resolve("source"));
        Files.delete(source.resolve(JmhResultJsonParser.RESULT_FILE_NAME));
        final Path root = directory.resolve("results");

        assertThatThrownBy(() -> BenchmarkResultArchiver.archive(Optional.of(runId()), source, root, TIMESTAMP))
                .isInstanceOf(IllegalStateException.class);

        assertThat(Files.notExists(root)).isTrue();
    }

    @Test
    @DisplayName("源目录缺少环境记录时应拒绝且不创建归档根")
    void archive_withoutEnvironmentRecord_shouldReject(@TempDir final Path directory) throws IOException {
        final Path source = sourceDirectory(directory.resolve("source"));
        Files.delete(source.resolve(EnvironmentRecordWriter.FILE_NAME));
        final Path root = directory.resolve("results");

        assertThatThrownBy(() -> BenchmarkResultArchiver.archive(Optional.of(runId()), source, root, TIMESTAMP))
                .isInstanceOf(IllegalStateException.class);

        assertThat(Files.notExists(root)).isTrue();
    }

    @Test
    @DisplayName("源结果 JSON 被同名目录占据时应拒绝且不创建归档根")
    void archive_withResultPathBeingDirectory_shouldReject(@TempDir final Path directory) throws IOException {
        final Path source = sourceDirectory(directory.resolve("source"));
        Files.delete(source.resolve(JmhResultJsonParser.RESULT_FILE_NAME));
        Files.createDirectory(source.resolve(JmhResultJsonParser.RESULT_FILE_NAME));
        final Path root = directory.resolve("results");

        assertThatThrownBy(() -> BenchmarkResultArchiver.archive(Optional.of(runId()), source, root, TIMESTAMP))
                .isInstanceOf(IllegalStateException.class);

        assertThat(Files.notExists(root)).isTrue();
    }

    @Test
    @DisplayName("源路径是普通文件时应拒绝")
    void archive_withSourcePathBeingFile_shouldReject(@TempDir final Path directory) throws IOException {
        final Path source = directory.resolve("source-file");
        Files.writeString(source, "not a directory");
        final Path root = directory.resolve("results");

        assertThatThrownBy(() -> BenchmarkResultArchiver.archive(Optional.of(runId()), source, root, TIMESTAMP))
                .isInstanceOf(IllegalStateException.class);

        assertThat(Files.notExists(root)).isTrue();
    }

    @Test
    @DisplayName("归档根被普通文件占据时应报告写入失败且源目录不变")
    void archive_withRootOccupiedByFile_shouldReportWriteFailure(@TempDir final Path directory) throws IOException {
        final Path source = sourceDirectory(directory.resolve("source"));
        final Path root = directory.resolve("results");
        Files.writeString(root, "not a directory");

        assertThatThrownBy(() -> BenchmarkResultArchiver.archive(Optional.of(runId()), source, root, TIMESTAMP))
                .isInstanceOf(UncheckedIOException.class);

        assertThat(Files.readString(root)).isEqualTo("not a directory");
        assertThat(entriesOf(source))
                .containsExactlyInAnyOrder(JmhResultJsonParser.RESULT_FILE_NAME, EnvironmentRecordWriter.FILE_NAME);
    }

    @Test
    @DisplayName("null 参数应被拒绝且不创建归档根")
    void archive_withNullArguments_shouldReject(@TempDir final Path directory) throws IOException {
        final Path source = sourceDirectory(directory.resolve("source"));
        final Path root = directory.resolve("results");

        assertThatThrownBy(() -> BenchmarkResultArchiver.archive(null, source, root, TIMESTAMP))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> BenchmarkResultArchiver.archive(Optional.of(runId()), null, root, TIMESTAMP))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> BenchmarkResultArchiver.archive(Optional.of(runId()), source, null, TIMESTAMP))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> BenchmarkResultArchiver.archive(Optional.of(runId()), source, root, null))
                .isInstanceOf(NullPointerException.class);

        assertThat(Files.notExists(root)).isTrue();
    }

    @Test
    @DisplayName("归档根应相对基准模块目录解析")
    void resolveResultsRoot_shouldResolveAgainstModuleDirectory(@TempDir final Path directory) {
        final Path moduleDirectory = directory.resolve("change-tracking-benchmark");

        final Path root = BenchmarkResultArchiver.resolveResultsRoot(moduleDirectory);

        assertThat(root).isEqualTo(moduleDirectory.resolve("benchmark").resolve("results"));
    }

    /**
     * Creates the run identifier used by these tests.
     *
     * @return the identifier of the archived run
     */
    private static BenchmarkRunId runId() {
        return new BenchmarkRunId("1.0-SNAPSHOT");
    }

    /**
     * Creates a run output directory holding both artefacts.
     *
     * @param source the directory to create
     * @return the created source directory
     * @throws IOException if the fixtures cannot be written
     */
    private static Path sourceDirectory(final Path source) throws IOException {
        Files.createDirectories(source);
        Files.writeString(source.resolve(JmhResultJsonParser.RESULT_FILE_NAME), RESULT_CONTENT);
        Files.writeString(source.resolve(EnvironmentRecordWriter.FILE_NAME), ENVIRONMENT_CONTENT);
        return source;
    }

    /**
     * Lists the entry names of the given directory.
     *
     * @param directory the directory to list
     * @return the sorted entry names
     * @throws IOException if the directory cannot be listed
     */
    private static List<String> entriesOf(final Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }
}