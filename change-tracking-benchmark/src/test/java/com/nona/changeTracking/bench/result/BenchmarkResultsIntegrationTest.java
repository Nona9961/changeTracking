package com.nona.changeTracking.bench.result;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nona.changeTracking.bench.env.EnvironmentRecordWriter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.jar.JarFile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Assembly level integration test of the benchmark result archive and comparison: a real JMH run
 * produces the two archived artefacts, archiving copies them below the module archive root without
 * touching an existing archive, the entry points archive and compare through the shaded benchmark
 * jar, and the repository rules keep an archived run out of git.
 * <p>
 * The test runs the benchmark itself instead of reading a pre-existing result file, because the
 * archive and the comparison are only meaningful on the products of a real run: an entry set with
 * both metrics, a score error to judge significance and an environment record of the run. Both runs
 * are shortened ({@code -f 1 -wi 2 -i 3 -w 200ms -r 200ms}) like the other assembly tests of this
 * module; the shortening weakens none of the checks here, because the number of entries follows from
 * the benchmark methods of the scanned class and the positivity of the two metrics does not depend on
 * the window length.
 * <p>
 * Like the other assembly tests of this module, this class is excluded by the default surefire
 * configuration ({@code **}{@code /}{@code *IntegrationTest}) and runs under {@code -Pfull} only. The
 * JMH run of this class needs the global JMH lock, so it is executed when no other benchmark runs.
 */
@DisplayName("BenchmarkResults 归档与对比装配面集成测试")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BenchmarkResultsIntegrationTest {

    /** 本模块目录，surefire 的工作目录，也是归档根与运行产物目录的基准。 */
    private static final Path MODULE_DIRECTORY = Path.of("").toAbsolutePath();

    /** 仓库根目录，shade 构建与仓库忽略规则检查在此执行。 */
    private static final Path REPOSITORY_DIRECTORY = MODULE_DIRECTORY.getParent();

    /** shade 产出的可执行基准 jar，真入口链路以它为类路径载体。 */
    private static final Path BENCHMARK_JAR = MODULE_DIRECTORY.resolve("target").resolve("benchmarks.jar");

    /** 本 task 专属的运行结果目录，与模块 smoke 及其他基准的结果目录隔离。 */
    private static final Path RUN_ROOT = MODULE_DIRECTORY.resolve("target").resolve("bench-results-run");

    /** 第一次真实运行的产物目录。 */
    private static final Path FIRST_RUN_DIRECTORY = RUN_ROOT.resolve("run-1");

    /** 第二次真实运行的产物目录。 */
    private static final Path SECOND_RUN_DIRECTORY = RUN_ROOT.resolve("run-2");

    /** 基准 setUp 写入环境记录的目录，与运行命令的参数一致。 */
    private static final Path BENCHMARK_RECORD_DIRECTORY =
            MODULE_DIRECTORY.resolve("target").resolve("benchmark-results");

    /** 只选择模块 smoke 基准，产物条目只来自它。 */
    private static final String BENCHMARK_SELECTOR = "ModuleSmokeBenchmark";

    /** 单位不一致拒绝用例自建的最小结果文档所使用的基准名，避开真实运行产物。 */
    private static final String SINGLE_ENTRY_BENCHMARK =
            "com.nona.changeTracking.bench.ModuleSmokeBenchmark.singleEntry";

    /** 归档端到端用例的运行标识。 */
    private static final String ARCHIVE_RUN_ID = "integration";

    /** 同秒冲突用例的运行标识。 */
    private static final String SEQUENCE_RUN_ID = "integration-seq";

    /** 真入口链路用例的运行标识。 */
    private static final String CLI_RUN_ID = "integration-cli";

    /** 仓库忽略规则复核用例的运行标识。 */
    private static final String IGNORE_RUN_ID = "integration-ignore";

    /** 时间指标的计量单位，由基准类的 {@code @OutputTimeUnit} 决定。 */
    private static final String TIME_UNIT = "us/op";

    /** 分配指标的字节计量单位。 */
    private static final String ALLOCATION_UNIT = "B/op";

    /** 带运行标识的归档目录名形态：标识、连字符、UTC 秒时间戳。 */
    private static final String NAMED_DIRECTORY_PATTERN =
            Pattern.quote(ARCHIVE_RUN_ID) + "-\\d{8}T\\d{6}Z";

    /** 不带运行标识的归档目录名形态：UTC 秒时间戳。 */
    private static final String TIMESTAMP_ONLY_PATTERN = "\\d{8}T\\d{6}Z";

    /** 独立渲染归档目录名中的 UTC 时间戳，作为目录名断言的期望值来源。 */
    private static final DateTimeFormatter UTC_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    /** 严格 JSON 读取环境记录的读取器。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** shade 构建的超时秒数。 */
    private static final long PACKAGE_TIMEOUT_SECONDS = 900;

    /** 一次缩短后 JMH 运行的超时秒数。 */
    private static final long RUN_TIMEOUT_SECONDS = 900;

    /** 一次入口进程的超时秒数。 */
    private static final long ENTRY_POINT_TIMEOUT_SECONDS = 120;

    /** 一次仓库忽略规则检查的超时秒数。 */
    private static final long CHECK_IGNORE_TIMEOUT_SECONDS = 30;

    /** 归档目录内的 JMH 结果文件名。 */
    private static final String RESULT_FILE_NAME = JmhResultJsonParser.RESULT_FILE_NAME;

    /** 归档目录内的环境记录文件名。 */
    private static final String ENVIRONMENT_FILE_NAME = EnvironmentRecordWriter.FILE_NAME;

    /** shade 构建的执行结果。 */
    private static ProcessResult packageResult;

    /** 第一次真实运行的执行结果。 */
    private static ProcessResult firstRunResult;

    /** 第二次真实运行的执行结果。 */
    private static ProcessResult secondRunResult;

    /** 本类执行期使用的临时目录，承载各用例自建的归档根。 */
    @TempDir
    static Path scratchDirectory;

    /**
     * shade 出可执行基准 jar，并以缩短的迭代参数真实运行两次模块 smoke 基准，把两次运行的产物
     * 分别落到本 task 专属目录；环境记录由运行期的基准 setUp 采集，运行后随本目录归档。
     *
     * @throws IOException 运行产物目录无法创建
     */
    @BeforeAll
    static void buildShadedJarAndRunBenchmarkTwice() throws IOException {
        packageResult = runCommand(List.of("mvn", "-B", "-o", "-DskipTests", "-pl", "change-tracking-benchmark",
                "-am", "-Pbench", "package"), REPOSITORY_DIRECTORY, PACKAGE_TIMEOUT_SECONDS);
        firstRunResult = runBenchmark(FIRST_RUN_DIRECTORY);
        secondRunResult = runBenchmark(SECOND_RUN_DIRECTORY);
    }

    @Test
    @Order(1)
    @DisplayName("两次真实运行应各自产出可解析的 JMH 结果，时间与分配指标均为正")
    void realRuns_shouldProduceParseableResults() throws IOException {
        assertThat(packageResult.exitCode())
                .as("shade build exit code, output tail: %s", tail(packageResult.output()))
                .isZero();
        assertThat(Files.isRegularFile(BENCHMARK_JAR)).as("shaded benchmark jar").isTrue();
        try (JarFile jar = new JarFile(BENCHMARK_JAR.toFile())) {
            assertThat(jar.getJarEntry(ArchiveResultsMain.class.getName().replace('.', '/') + ".class"))
                    .as("archive entry point inside the shaded benchmark jar")
                    .isNotNull();
            assertThat(jar.getJarEntry(CompareResultsMain.class.getName().replace('.', '/') + ".class"))
                    .as("comparison entry point inside the shaded benchmark jar")
                    .isNotNull();
        }
        assertThat(firstRunResult.exitCode())
                .as("first jmh run exit code, output tail: %s", tail(firstRunResult.output()))
                .isZero();
        assertThat(secondRunResult.exitCode())
                .as("second jmh run exit code, output tail: %s", tail(secondRunResult.output()))
                .isZero();
        assertThat(Files.isRegularFile(resultFileOf(FIRST_RUN_DIRECTORY))).as("first run result json").isTrue();
        assertThat(Files.isRegularFile(resultFileOf(SECOND_RUN_DIRECTORY))).as("second run result json").isTrue();
        assertThat(Files.isRegularFile(environmentRecordOf(FIRST_RUN_DIRECTORY)))
                .as("environment record archived next to the first run result")
                .isTrue();
        assertThat(Files.isRegularFile(environmentRecordOf(SECOND_RUN_DIRECTORY)))
                .as("environment record archived next to the second run result")
                .isTrue();

        final List<BenchmarkResultEntry> firstEntries = JmhResultJsonParser.parse(resultFileOf(FIRST_RUN_DIRECTORY));
        final List<BenchmarkResultEntry> secondEntries = JmhResultJsonParser.parse(resultFileOf(SECOND_RUN_DIRECTORY));

        assertThat(firstEntries).as("entries of the real run").isNotEmpty();
        assertThat(secondEntries).as("entries of the repeated run").hasSameSizeAs(firstEntries);
        assertThat(firstEntries).allSatisfy(entry -> {
            assertThat(entry.benchmark()).as("benchmark of the entry").contains(BENCHMARK_SELECTOR);
            assertThat(entry.primaryMetric().scoreUnit()).as("time unit of %s", entry.benchmark()).isEqualTo(TIME_UNIT);
            assertThat(entry.primaryMetric().score()).as("time per operation of %s", entry.benchmark()).isPositive();
            assertThat(entry.allocationMetric().scoreUnit())
                    .as("allocation unit of %s", entry.benchmark())
                    .isEqualTo(ALLOCATION_UNIT);
            assertThat(entry.allocationMetric().score())
                    .as("allocation per operation of %s", entry.benchmark())
                    .isPositive();
        });
    }

    @Test
    @Order(2)
    @DisplayName("归档应把两份运行产物原样落到模块归档根下 <标识>-<UTC 秒> 目录，环境记录含四项")
    void archiver_shouldArchiveBothRunArtefactsBelowTheModuleArchiveRoot() throws IOException {
        final BenchmarkRunId runId = new BenchmarkRunId(ARCHIVE_RUN_ID);
        final Instant timestamp = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        final Path resultsRoot = BenchmarkResultArchiver.resolveResultsRoot(MODULE_DIRECTORY);

        final Path archiveDirectory = BenchmarkResultArchiver.archive(
                Optional.of(runId), FIRST_RUN_DIRECTORY, resultsRoot, timestamp);

        assertThat(archiveDirectory.getParent()).as("archive parent below the module archive root").isEqualTo(resultsRoot);
        assertThat(archiveDirectory.getFileName().toString())
                .as("archive directory name of a run carrying an identifier")
                .matches(NAMED_DIRECTORY_PATTERN);
        assertThat(archiveDirectory.getFileName().toString())
                .as("archive directory name derived from the run identifier and the archival instant")
                .isEqualTo(runId.value() + "-" + UTC_TIMESTAMP.format(timestamp));
        assertThat(entriesOf(archiveDirectory))
                .as("artefacts of the archived run")
                .containsExactlyInAnyOrder(RESULT_FILE_NAME, ENVIRONMENT_FILE_NAME);
        assertThat(Files.mismatch(resultFileOf(archiveDirectory), resultFileOf(FIRST_RUN_DIRECTORY)))
                .as("archived result file is byte identical to the run artefact")
                .isEqualTo(-1L);
        assertThat(Files.mismatch(environmentRecordOf(archiveDirectory), environmentRecordOf(FIRST_RUN_DIRECTORY)))
                .as("archived environment record is byte identical to the run artefact")
                .isEqualTo(-1L);

        assertEnvironmentRecordMatchesRunningJvm(environmentRecordOf(archiveDirectory));
    }

    @Test
    @Order(3)
    @DisplayName("同一秒内归档第二次运行应落到 -2，第一次归档保持原样")
    void archiver_shouldMoveTheSecondArchiveOfTheSameSecondToTheNextSequenceNumber() throws IOException {
        final BenchmarkRunId runId = new BenchmarkRunId(SEQUENCE_RUN_ID);
        final Instant timestamp = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        final Path resultsRoot = scratchDirectory.resolve("conflict-root");
        final ArchiveDirectoryName baseName = ArchiveDirectoryName.of(Optional.of(runId), timestamp);

        final Path firstArchive = BenchmarkResultArchiver.archive(
                Optional.of(runId), FIRST_RUN_DIRECTORY, resultsRoot, timestamp);

        assertThat(firstArchive.getFileName().toString()).isEqualTo(baseName.name());
        assertThat(Files.mismatch(resultFileOf(FIRST_RUN_DIRECTORY), resultFileOf(SECOND_RUN_DIRECTORY)))
                .as("the two runs must differ, otherwise an overwrite of the first archive is invisible")
                .isNotEqualTo(-1L);

        final Path secondArchive = BenchmarkResultArchiver.archive(
                Optional.of(runId), SECOND_RUN_DIRECTORY, resultsRoot, timestamp);

        assertThat(secondArchive.getFileName().toString())
                .as("directory name of the archive of a taken instant")
                .isEqualTo(baseName.withSequence(2).name());
        assertThat(entriesOf(resultsRoot))
                .as("archive directories of the same run and second")
                .containsExactlyInAnyOrder(baseName.name(), baseName.withSequence(2).name());
        assertThat(Files.mismatch(resultFileOf(firstArchive), resultFileOf(FIRST_RUN_DIRECTORY)))
                .as("first archive stays the product of the first run")
                .isEqualTo(-1L);
        assertThat(Files.mismatch(resultFileOf(secondArchive), resultFileOf(SECOND_RUN_DIRECTORY)))
                .as("second archive holds the product of the second run")
                .isEqualTo(-1L);
    }

    @Test
    @Order(4)
    @DisplayName("归档入口经真实入口链路应支持带 --id 与省略 --id 两条路径，被拒请求退出码为 1")
    void archiveEntryPoint_shouldArchiveWithAndWithoutIdentifier() throws IOException {
        final Path withIdRoot = scratchDirectory.resolve("entry-point-with-id");
        final Path withoutIdRoot = scratchDirectory.resolve("entry-point-without-id");

        final EntryPointResult withId = runEntryPoint(ArchiveResultsMain.class, List.of(
                "--id", CLI_RUN_ID,
                "--source", FIRST_RUN_DIRECTORY.toString(),
                "--root", withIdRoot.toString()));

        assertThat(withId.exitCode())
                .as("archive entry point with --id, output tail: %s", tail(withId.output()))
                .isZero();
        assertThat(entriesOf(withIdRoot)).as("archive directory of the run with an identifier").singleElement()
                .satisfies(name -> assertThat(name).matches(Pattern.quote(CLI_RUN_ID) + "-\\d{8}T\\d{6}Z"));
        final Path withIdArchiveDirectory = withIdRoot.resolve(onlyEntryOf(withIdRoot));
        assertThat(withId.standardOutput())
                .as("archive directory of the run with an identifier on standard output, standard error: %s",
                        tail(withId.standardError()))
                .contains(withIdArchiveDirectory.toString());
        assertThat(Files.mismatch(resultFileOf(withIdRoot.resolve(onlyEntryOf(withIdRoot))),
                resultFileOf(FIRST_RUN_DIRECTORY)))
                .as("archived result file of the entry point run")
                .isEqualTo(-1L);

        final EntryPointResult withoutId = runEntryPoint(ArchiveResultsMain.class, List.of(
                "--source", SECOND_RUN_DIRECTORY.toString(),
                "--root", withoutIdRoot.toString()));

        assertThat(withoutId.exitCode())
                .as("archive entry point without --id, output tail: %s", tail(withoutId.output()))
                .isZero();
        assertThat(entriesOf(withoutIdRoot)).as("archive directory of the run without an identifier").singleElement()
                .satisfies(name -> assertThat(name).matches(TIMESTAMP_ONLY_PATTERN));
        final Path withoutIdArchiveDirectory = withoutIdRoot.resolve(onlyEntryOf(withoutIdRoot));
        assertThat(withoutId.standardOutput())
                .as("archive directory of the run without an identifier on standard output, standard error: %s",
                        tail(withoutId.standardError()))
                .contains(withoutIdArchiveDirectory.toString());
        assertThat(Files.mismatch(resultFileOf(withoutIdRoot.resolve(onlyEntryOf(withoutIdRoot))),
                resultFileOf(SECOND_RUN_DIRECTORY)))
                .as("archived result file of the entry point run without an identifier")
                .isEqualTo(-1L);

        final Path rejectedRoot = scratchDirectory.resolve("entry-point-rejected");
        final Path missingSource = scratchDirectory.resolve("missing-source");
        final EntryPointResult rejected = runEntryPoint(ArchiveResultsMain.class, List.of(
                "--source", missingSource.toString(),
                "--root", rejectedRoot.toString()));

        assertThat(rejected.exitCode())
                .as("exit code of a rejected archive request is 1, output tail: %s", tail(rejected.output()))
                .isEqualTo(1);
        assertThat(rejected.standardError())
                .as("diagnostic of a rejected archive request on standard error")
                .contains("source directory")
                .contains(missingSource.toString());
        assertThat(Files.notExists(rejectedRoot))
                .as("a rejected archive request must not create the archive root")
                .isTrue();
    }

    @Test
    @Order(5)
    @DisplayName("两次真实运行的差异表应覆盖全部条目双向指标，对比入口的异常路径退出码为 1")
    void comparator_shouldProduceTheDifferenceTableOfTwoRealRuns() throws IOException {
        final List<BenchmarkResultEntry> firstEntries = JmhResultJsonParser.parse(resultFileOf(FIRST_RUN_DIRECTORY));

        final ResultDiff diff = BenchmarkResultComparator.compare(
                resultFileOf(FIRST_RUN_DIRECTORY), resultFileOf(SECOND_RUN_DIRECTORY));

        assertThat(diff.incomparable()).as("entries only one side holds").isEmpty();
        assertThat(diff.deltas()).as("metric deltas of two runs of the same benchmark")
                .hasSize(2 * firstEntries.size());
        for (final BenchmarkResultEntry entry : firstEntries) {
            assertThat(diff.deltas()).as("metrics compared for the entry %s", entry.key())
                    .filteredOn(delta -> delta.entryKey().equals(entry.key()))
                    .extracting(MetricDelta::metricName)
                    .containsExactlyInAnyOrder(BenchmarkResultComparator.PRIMARY_METRIC_NAME,
                            BenchmarkResultComparator.ALLOCATION_METRIC_NAME);
        }

        final String table = diff.toTable();
        assertThat(table.lines()).as("table lines of header, deltas and incomparable entries")
                .hasSize(2 + diff.deltas().size() + diff.incomparable().size());
        assertThat(table).contains(resultFileOf(FIRST_RUN_DIRECTORY).toString());
        assertThat(table).contains(resultFileOf(SECOND_RUN_DIRECTORY).toString());
        assertThat(table).contains(firstEntries.get(0).key());

        final EntryPointResult compared = runEntryPoint(CompareResultsMain.class, List.of(
                "--first", resultFileOf(FIRST_RUN_DIRECTORY).toString(),
                "--second", resultFileOf(SECOND_RUN_DIRECTORY).toString()));

        assertThat(compared.exitCode())
                .as("comparison entry point, output tail: %s", tail(compared.output()))
                .isZero();
        assertThat(compared.standardOutput())
                .as("difference table on standard output, standard error: %s", tail(compared.standardError()))
                .contains("Benchmark result diff:")
                .contains("metric|entry|first|second|delta|significance")
                .contains(firstEntries.get(0).key())
                .contains(table);

        final EntryPointResult rejected = runEntryPoint(CompareResultsMain.class, List.of(
                "--first", resultFileOf(FIRST_RUN_DIRECTORY).toString()));

        assertThat(rejected.exitCode())
                .as("exit code of an incomplete comparison command line is 1, output tail: %s", tail(rejected.output()))
                .isEqualTo(1);
        assertThat(rejected.standardError())
                .as("diagnostic of an incomplete comparison command line on standard error")
                .contains(CompareResultsMain.SECOND_OPTION);

        final Path mismatchingUnitFirst = scratchDirectory.resolve("unit-mismatch-first.json");
        final Path mismatchingUnitSecond = scratchDirectory.resolve("unit-mismatch-second.json");
        Files.writeString(mismatchingUnitFirst, entryDocumentOf(TIME_UNIT));
        Files.writeString(mismatchingUnitSecond, entryDocumentOf("ms/op"));

        final EntryPointResult rejectedForUnit = runEntryPoint(CompareResultsMain.class, List.of(
                "--first", mismatchingUnitFirst.toString(),
                "--second", mismatchingUnitSecond.toString()));

        assertThat(rejectedForUnit.exitCode())
                .as("exit code of two results whose time units differ is 1, output tail: %s", tail(rejectedForUnit.output()))
                .isEqualTo(1);
        assertThat(rejectedForUnit.standardError())
                .as("diagnostic of two results whose time units differ on standard error")
                .contains(TIME_UNIT)
                .contains("ms/op");
    }

    @Test
    @Order(6)
    @DisplayName("真实归档的产物与归档目录应被仓库忽略规则命中")
    void archivedRun_shouldBeIgnoredByTheRepositoryRules() throws IOException {
        final BenchmarkRunId runId = new BenchmarkRunId(IGNORE_RUN_ID);
        final Instant timestamp = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        final Path archiveDirectory = BenchmarkResultArchiver.archive(Optional.of(runId),
                FIRST_RUN_DIRECTORY, BenchmarkResultArchiver.resolveResultsRoot(MODULE_DIRECTORY), timestamp);

        final ProcessResult artefact = checkIgnore(
                REPOSITORY_DIRECTORY.relativize(resultFileOf(archiveDirectory)).toString());
        assertThat(artefact.exitCode()).as("git check-ignore of the archived artefact: %s", artefact.output()).isZero();
        assertThat(artefact.output()).as("rule matching the archived artefact").contains(".gitignore");

        final ProcessResult directory = checkIgnore(REPOSITORY_DIRECTORY.relativize(archiveDirectory).toString());
        assertThat(directory.exitCode()).as("git check-ignore of the archive directory: %s", directory.output()).isZero();
        assertThat(directory.output()).as("rule matching the archive directory").contains(".gitignore");
    }

    @Test
    @Order(7)
    @DisplayName("归档根应相对模块目录解析为模块内的 benchmark/results")
    void resultsRoot_shouldResolveAgainstTheModuleDirectory() {
        assertThat(BenchmarkResultArchiver.DEFAULT_RESULTS_ROOT.isAbsolute())
                .as("the configured archive root is relative to the module directory")
                .isFalse();
        assertThat(BenchmarkResultArchiver.resolveResultsRoot(MODULE_DIRECTORY))
                .isEqualTo(MODULE_DIRECTORY.resolve("benchmark").resolve("results"));
        assertThat(BenchmarkResultArchiver.resolveResultsRoot(scratchDirectory.resolve("other-module")))
                .isEqualTo(scratchDirectory.resolve("other-module").resolve("benchmark").resolve("results"));
    }

    /**
     * 断言归档的环境记录与本次运行的真实环境对应：JDK 版本、JVM 参数、GC 收集器与机器标识四项。
     *
     * @param environmentRecord 归档的环境记录文件
     * @throws IOException 环境记录无法读取
     */
    private static void assertEnvironmentRecordMatchesRunningJvm(final Path environmentRecord) throws IOException {
        final JsonNode environment = MAPPER.readTree(Files.readString(environmentRecord));

        assertThat(environment.get("jdkVersion").asText())
                .as("jdk version of the benchmark jvm")
                .isEqualTo(System.getProperty("java.version"));
        final List<String> jvmArguments = textsOf(environment.get("jvmArguments"));
        assertThat(jvmArguments).as("jvm arguments of the benchmark jvm").isNotEmpty();
        assertThat(jvmArguments).allSatisfy(argument -> assertThat(argument).startsWith("-"));
        assertThat(textsOf(environment.get("gcCollectors")))
                .as("gc collectors of the benchmark jvm")
                .isNotEmpty()
                .isSubsetOf(runningGcCollectorNames());
        assertThat(environment.at("/machine/hostName").asText()).as("host name of the benchmark host").isNotBlank();
        assertThat(environment.at("/machine/availableProcessors").asInt())
                .as("available processors of the benchmark host")
                .isGreaterThanOrEqualTo(1);
        assertThat(environment.at("/machine/maxMemoryBytes").asLong())
                .as("max memory of the benchmark jvm")
                .isPositive();
    }

    /**
     * 以缩短的 JMH 参数真实运行一次模块 smoke 基准，并把该次运行的环境记录复制进运行产物目录。
     *
     * @param runDirectory 该次运行的产物目录
     * @return 该次 JMH 运行的执行结果
     * @throws IOException 产物目录无法创建或环境记录无法复制
     */
    private static ProcessResult runBenchmark(final Path runDirectory) throws IOException {
        Files.createDirectories(runDirectory);
        final List<String> command = new ArrayList<>(
                List.of("java", "-jar", BENCHMARK_JAR.toString(), BENCHMARK_SELECTOR));
        command.addAll(List.of(
                "-f", "1",
                "-wi", "2",
                "-i", "3",
                "-w", "200ms",
                "-r", "200ms",
                "-prof", "gc",
                "-rf", "json",
                "-rff", resultFileOf(runDirectory).toString()));
        final ProcessResult result = runCommand(command, MODULE_DIRECTORY, RUN_TIMEOUT_SECONDS);
        final Path collected = BENCHMARK_RECORD_DIRECTORY.resolve(ENVIRONMENT_FILE_NAME);
        if (Files.isRegularFile(collected)) {
            Files.copy(collected, environmentRecordOf(runDirectory), StandardCopyOption.REPLACE_EXISTING);
        }
        return result;
    }

    /**
     * 渲染一份只含一个条目的最小 JMH 结果文档，作为单位不一致拒绝路径的自建输入（该用例不读真实
     * 运行产物，因此不受基准运行结果影响）。
     *
     * @param timeUnit 时间指标的单位
     * @return JMH 原生结果文档
     */
    private static String entryDocumentOf(final String timeUnit) {
        return """
                [
                  {
                    "benchmark" : "%s",
                    "params" : { "single" : "1" },
                    "primaryMetric" : { "score" : 100.0, "scoreError" : 1.0, "scoreUnit" : "%s" },
                    "secondaryMetrics" : {
                      "gc.alloc.rate.norm" : { "score" : 1024.0, "scoreError" : 500.0, "scoreUnit" : "B/op" }
                    }
                  }
                ]""".formatted(SINGLE_ENTRY_BENCHMARK, timeUnit);
    }

    /**
     * 经 shaded 基准 jar 以独立进程执行一个入口类，并把标准输出与标准错误分别捕获：只断言退出码
     * 的用例读 {@link EntryPointResult#exitCode()}，输出契约的用例读
     * {@link EntryPointResult#standardOutput()} 与 {@link EntryPointResult#standardError()}。
     *
     * @param entryPoint 入口类
     * @param arguments  入口类的命令行参数
     * @return 入口进程的退出码、标准输出与标准错误
     */
    private static EntryPointResult runEntryPoint(final Class<?> entryPoint, final List<String> arguments) {
        final List<String> command = new ArrayList<>(
                List.of("java", "-cp", BENCHMARK_JAR.toString(), entryPoint.getName()));
        command.addAll(arguments);
        final Path standardOutputFile;
        final Path standardErrorFile;
        try {
            standardOutputFile = Files.createTempFile("benchmark-entry-point-out-", ".log");
            standardOutputFile.toFile().deleteOnExit();
            standardErrorFile = Files.createTempFile("benchmark-entry-point-err-", ".log");
            standardErrorFile.toFile().deleteOnExit();
        } catch (final IOException failure) {
            throw new UncheckedIOException("Failed to create the entry point stream files", failure);
        }
        final ProcessBuilder builder = new ProcessBuilder(new ArrayList<>(command));
        builder.directory(MODULE_DIRECTORY.toFile());
        builder.redirectOutput(standardOutputFile.toFile());
        builder.redirectError(standardErrorFile.toFile());
        try {
            final Process process = builder.start();
            if (!process.waitFor(ENTRY_POINT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                final String timedOut =
                        "entry point timed out after " + ENTRY_POINT_TIMEOUT_SECONDS + "s: " + command;
                return new EntryPointResult(-1, timedOut, timedOut);
            }
            return new EntryPointResult(process.exitValue(),
                    Files.readString(standardOutputFile), Files.readString(standardErrorFile));
        } catch (final IOException failure) {
            throw new UncheckedIOException("Failed to run " + command, failure);
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running " + command, interrupted);
        }
    }

    /**
     * 询问 git 是否忽略给定的仓库相对路径。
     *
     * @param relativePath 仓库相对路径
     * @return {@code git check-ignore -v --no-index} 的退出码与合并输出
     */
    private static ProcessResult checkIgnore(final String relativePath) {
        return runCommand(List.of("git", "check-ignore", "-v", "--no-index", relativePath),
                REPOSITORY_DIRECTORY, CHECK_IGNORE_TIMEOUT_SECONDS);
    }

    /**
     * 在指定目录执行外部命令，并把合并后的输出落盘后读回。
     *
     * @param command          命令与参数
     * @param workingDirectory 工作目录
     * @param timeoutSeconds   超时秒数
     * @return 退出码与合并输出
     */
    private static ProcessResult runCommand(final List<String> command,
                                            final Path workingDirectory,
                                            final long timeoutSeconds) {
        final Path logFile;
        try {
            logFile = Files.createTempFile("benchmark-results-integration-", ".log");
            logFile.toFile().deleteOnExit();
        } catch (final IOException failure) {
            throw new UncheckedIOException("Failed to create the command log file", failure);
        }
        final ProcessBuilder builder = new ProcessBuilder(new ArrayList<>(command));
        builder.directory(workingDirectory.toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(logFile.toFile());
        try {
            final Process process = builder.start();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new ProcessResult(-1, "command timed out after " + timeoutSeconds + "s: " + command);
            }
            return new ProcessResult(process.exitValue(), Files.readString(logFile));
        } catch (final IOException failure) {
            throw new UncheckedIOException("Failed to run " + command, failure);
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running " + command, interrupted);
        }
    }

    /**
     * 返回一次运行的 JMH 结果文件路径。
     *
     * @param runDirectory 该次运行的产物目录
     * @return 该目录内的结果文件路径
     */
    private static Path resultFileOf(final Path runDirectory) {
        return runDirectory.resolve(RESULT_FILE_NAME);
    }

    /**
     * 返回一次运行的环境记录文件路径。
     *
     * @param runDirectory 该次运行的产物目录
     * @return 该目录内的环境记录路径
     */
    private static Path environmentRecordOf(final Path runDirectory) {
        return runDirectory.resolve(ENVIRONMENT_FILE_NAME);
    }

    /**
     * 列出目录内的条目名。
     *
     * @param directory 待列出的目录
     * @return 排序后的条目名
     * @throws IOException 目录无法列出
     */
    private static List<String> entriesOf(final Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    /**
     * 返回目录内的唯一条目名。
     *
     * @param directory 只含一个条目的目录
     * @return 该目录唯一条目的名字
     * @throws IOException 目录无法列出
     */
    private static String onlyEntryOf(final Path directory) throws IOException {
        final List<String> entries = entriesOf(directory);
        if (entries.size() != 1) {
            throw new NoSuchElementException("Expected exactly one entry below " + directory + ", got " + entries);
        }
        return entries.get(0);
    }

    /**
     * 读取 JSON 字符串数组的文本值。
     *
     * @param array JSON 数组节点
     * @return 数组内的文本值
     */
    private static List<String> textsOf(final JsonNode array) {
        final List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }

    /**
     * 返回当前 JVM 的 GC 收集器名集合，作为基准 JVM 收集器名的对照基准。
     *
     * @return 当前 JVM 的 GC 收集器名
     */
    private static Set<String> runningGcCollectorNames() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .map(GarbageCollectorMXBean::getName)
                .collect(Collectors.toSet());
    }

    /**
     * 截取输出尾部，用于失败断言的消息。
     *
     * @param output 命令输出
     * @return 输出最后 2000 个字符
     */
    private static String tail(final String output) {
        final int start = Math.max(0, output.length() - 2000);
        return output.substring(start);
    }

    /**
     * 外部命令的执行结果。
     *
     * @param exitCode 退出码，超时为 -1
     * @param output   合并后的标准输出与标准错误
     */
    private record ProcessResult(int exitCode, String output) {
    }

    /**
     * 入口进程的执行结果：退出码与两条流分开保留，输出契约的断言分别检查标准输出与标准错误。
     *
     * @param exitCode       退出码，超时为 -1
     * @param standardOutput 标准输出
     * @param standardError  标准错误
     */
    private record EntryPointResult(int exitCode, String standardOutput, String standardError) {

        /**
         * 合并两条流，仅用于断言失败消息，不作为断言依据。
         *
         * @return 标准输出后接标准错误
         */
        String output() {
            return standardOutput + standardError;
        }
    }
}