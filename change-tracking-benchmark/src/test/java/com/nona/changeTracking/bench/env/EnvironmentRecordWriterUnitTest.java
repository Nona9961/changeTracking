package com.nona.changeTracking.bench.env;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link EnvironmentRecordWriter}: file content, directory
 * creation, overwrite, and failure without leftovers.
 */
@DisplayName("EnvironmentRecordWriter 环境记录落盘单元测试")
class EnvironmentRecordWriterUnitTest {

    @Test
    @DisplayName("写入应生成 environment.json，内容与记录的 JSON 一致")
    void write_shouldCreateFileWithJsonContent(@TempDir final Path directory) throws IOException {
        final EnvironmentRecord record = sampleRecord();

        final Path written = EnvironmentRecordWriter.write(record, directory);

        assertThat(written).isEqualTo(directory.resolve(EnvironmentRecordWriter.FILE_NAME));
        assertThat(Files.readString(written)).isEqualTo(record.toJson());
    }

    @Test
    @DisplayName("目标目录不存在时应逐级创建")
    void write_shouldCreateMissingDirectories(@TempDir final Path directory) throws IOException {
        final Path output = directory.resolve("benchmark-results").resolve("1.0-SNAPSHOT");

        final Path written = EnvironmentRecordWriter.write(sampleRecord(), output);

        assertThat(Files.exists(written)).isTrue();
        assertThat(Files.readString(written)).isEqualTo(sampleRecord().toJson());
    }

    @Test
    @DisplayName("重复写入应覆盖为最新记录")
    void write_calledTwice_shouldOverwriteWithLatestRecord(@TempDir final Path directory) throws IOException {
        final EnvironmentRecord first = sampleRecord();
        final EnvironmentRecord second = new EnvironmentRecord(
                "25.0.4", List.of("-Xmx4g"), List.of("Serial"), new MachineIdentity("other-host", 4, 1L));

        EnvironmentRecordWriter.write(first, directory);
        final Path written = EnvironmentRecordWriter.write(second, directory);

        assertThat(Files.readString(written)).isEqualTo(second.toJson());
    }

    @Test
    @DisplayName("目标路径被普通文件占用时应拒绝且不产生残留")
    void write_withPathOccupiedByFile_shouldReject(@TempDir final Path directory) throws IOException {
        final Path occupied = directory.resolve("occupied");
        Files.writeString(occupied, "not a directory");

        assertThatThrownBy(() -> EnvironmentRecordWriter.write(sampleRecord(), occupied))
                .isInstanceOf(UncheckedIOException.class);
        assertThat(entriesOf(directory)).containsExactly("occupied");
    }

    @Test
    @DisplayName("父路径含普通文件导致目录不可创建时应拒绝且不产生残留")
    void write_withBlockedParentPath_shouldReject(@TempDir final Path directory) throws IOException {
        final Path blocker = directory.resolve("blocker");
        Files.writeString(blocker, "blocking file");

        assertThatThrownBy(() -> EnvironmentRecordWriter.write(sampleRecord(), blocker.resolve("child")))
                .isInstanceOf(UncheckedIOException.class);
        assertThat(entriesOf(directory)).containsExactly("blocker");
    }

    @Test
    @DisplayName("记录或目录为 null 应拒绝")
    void write_withNullArguments_shouldReject(@TempDir final Path directory) {
        assertThatThrownBy(() -> EnvironmentRecordWriter.write(null, directory))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> EnvironmentRecordWriter.write(sampleRecord(), null))
                .isInstanceOf(NullPointerException.class);
    }

    private static EnvironmentRecord sampleRecord() {
        return new EnvironmentRecord(
                "25.0.4",
                List.of("-Xmx2g", "-XX:+UseG1GC"),
                List.of("G1 Young Generation", "G1 Old Generation"),
                new MachineIdentity("bench-host", 8, 2_147_483_648L));
    }

    private static List<String> entriesOf(final Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }
}
