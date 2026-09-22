package com.nona.changeTracking.bench;

import com.nona.changeTracking.bench.sample.SampleFamily;
import com.nona.changeTracking.bench.sample.SampleOrder;
import com.nona.changeTracking.bench.sample.SampleOrderSummary;
import com.nona.changeTracking.bench.sample.SampleShape;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Assembly level integration test of {@link SnapshotBuildBenchmark}: the scanned benchmark enters a
 * real JMH run through the shaded {@code benchmarks.jar} and produces usable metrics, the real
 * assembly chain (shaded SPI plus the sample family on every scanned level) is usable, and the level
 * effect of the collection size dimension stays observable in both the time and the allocation
 * metric.
 * <p>
 * The JMH run of this test shortens the frozen warmup and measurement windows
 * ({@code -wi 2 -i 3 -w 200ms -r 200ms}) to keep the integration test short. The shortening weakens
 * none of the three criteria checked here: the ten entries and their parameters come from the
 * declared {@code @Param} levels, which a shortened run expands unchanged; the positivity of the time
 * and allocation metrics does not depend on the window length; and the collection size level effect
 * stays far above the asserted factor, because the measured operation grows with the collection it
 * snapshots.
 * <p>
 * Like {@code BenchmarkModuleIntegrationTest}, this class is excluded by the default surefire
 * configuration ({@code **}{@code /}{@code *IntegrationTest}) and runs under {@code -Pfull} only. A
 * failing JMH run still exits with code zero and writes zero entries, so every assertion here works on
 * the strictly parsed result json instead of the exit code alone.
 */
@DisplayName("SnapshotBuildBenchmark 装配面集成测试")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SnapshotBuildBenchmarkIntegrationTest {

    /** 本模块目录，surefire 的工作目录。 */
    private static final Path MODULE_DIRECTORY = Path.of("").toAbsolutePath();

    /** 仓库根目录，shade 构建在此执行。 */
    private static final Path REPOSITORY_DIRECTORY = MODULE_DIRECTORY.getParent();

    /** shade 产出的可执行基准 jar。 */
    private static final Path BENCHMARK_JAR = MODULE_DIRECTORY.resolve("target").resolve("benchmarks.jar");

    /** 本次集成测试的运行结果目录，与模块 smoke 测试的结果目录隔离。 */
    private static final Path RESULT_DIRECTORY = MODULE_DIRECTORY.resolve("target").resolve("snapshot-scan-results");

    /** 本次运行的 JMH 原生 JSON 结果文件。 */
    private static final Path JMH_RESULT_FILE = RESULT_DIRECTORY.resolve("jmh-result.json");

    /** 只选择本 task 的基准类，不混入模块 smoke 基准的条目。 */
    private static final String BENCHMARK_SELECTOR = "SnapshotBuildBenchmark";

    /** shade 后 core 的 SPI 服务文件路径。 */
    private static final String SPI_SERVICE_ENTRY =
            "META-INF/services/com.nona.changeTracking.spi.TrackingCapabilityProvider";

    /** JMH 注解处理器生成的基准清单，其存在说明基准类真正进入了可执行 jar。 */
    private static final String BENCHMARK_LIST_ENTRY = "META-INF/BenchmarkList";

    /** shade 后应可见的 core 追踪能力提供者。 */
    private static final String CORE_PROVIDER =
            "com.nona.changeTracking.internal.capability.DefaultTrackingCapabilityProvider";

    /** 严格 JSON 解析输出行的前缀。 */
    private static final String ENTRY_PREFIX = "ENTRY|";

    /** 集合规模维的最小档位，档位效应的对照基准。 */
    private static final int SMALL_COLLECTION_SIZE = 10;

    /** 集合规模维的最大档位，档位效应的对照基准。 */
    private static final int LARGE_COLLECTION_SIZE = 1_000;

    /** 档位效应判据：大档位指标不低于小档位的该倍数。 */
    private static final double LEVEL_EFFECT_FACTOR = 3.0;

    /** 冻结的条目总数：3 个测量方法 × 2/5/3 个档位。 */
    private static final int EXPECTED_ENTRY_COUNT = 10;

    /** 集合规模维的方法名。 */
    private static final String COLLECTION_SIZE_METHOD = "trackByCollectionSize";

    /** 字段数维的方法名。 */
    private static final String FIELD_COUNT_METHOD = "trackByFieldCount";

    /** 嵌套深度维的方法名。 */
    private static final String NESTING_DEPTH_METHOD = "trackByNestingDepth";

    /** 测量的平均时间单位，由 {@code @OutputTimeUnit(MICROSECONDS)} 决定。 */
    private static final String TIME_UNIT = "us/op";

    /** 分配指标的字节计量单位。 */
    private static final String ALLOCATION_UNIT = "B/op";

    /** 冻结的档位：方法名 → 该维全部档位。 */
    private static final Map<String, List<Integer>> FROZEN_LEVELS_BY_METHOD = Map.of(
            FIELD_COUNT_METHOD, List.of(SampleShape.SUPPORTED_FIELD_COUNT_LOW, SampleShape.SUPPORTED_FIELD_COUNT_HIGH),
            NESTING_DEPTH_METHOD, List.of(1, 2, 3, 4, 5),
            COLLECTION_SIZE_METHOD, List.of(SMALL_COLLECTION_SIZE, 100, LARGE_COLLECTION_SIZE));

    /** 冻结的扫描维：方法名 → 该维的 {@code @Param} 字段名。 */
    private static final Map<String, String> SCANNED_DIMENSION_BY_METHOD = Map.of(
            FIELD_COUNT_METHOD, "fieldCount",
            NESTING_DEPTH_METHOD, "nestingDepth",
            COLLECTION_SIZE_METHOD, "collectionSize");

    /** 缩短后的 JMH 运行参数：单 fork、2 次预热、3 次测量、各 200ms，并挂载 gc profiler。 */
    private static final List<String> JMH_OPTIONS = List.of(
            "-f", "1",
            "-wi", "2",
            "-i", "3",
            "-w", "200ms",
            "-r", "200ms",
            "-prof", "gc",
            "-rf", "json",
            "-rff", JMH_RESULT_FILE.toString());

    /** shade 构建的超时秒数。 */
    private static final long PACKAGE_TIMEOUT_SECONDS = 600;

    /** 缩短后 JMH 运行的超时秒数。 */
    private static final long RUN_TIMEOUT_SECONDS = 900;

    /** 严格 JSON 解析脚本：条目数、档位唯一性与指标齐备在此断言，输出行由 Java 侧断言。 */
    private static final String JMH_PARSE_SCRIPT = """
            import json, sys
            with open(sys.argv[1], encoding="utf-8") as handle:
                data = json.load(handle)
            assert isinstance(data, list), "result json is not a list: %r" % type(data)
            assert len(data) == 10, "expected 10 benchmark entries, got %d" % len(data)
            for result in data:
                params = result.get("params") or {}
                assert len(params) == 1, "expected one scanned parameter in %s, got %r" % (
                    result.get("benchmark", ""), params)
                primary = result.get("primaryMetric") or {}
                alloc = (result.get("secondaryMetrics") or {}).get("gc.alloc.rate.norm") or {}
                assert primary.get("score") is not None, "missing time score in %s" % result.get("benchmark", "")
                assert alloc.get("score") is not None, "missing allocation score in %s" % result.get("benchmark", "")
                name, value = next(iter(params.items()))
                print("ENTRY|%s|%s=%s|%s|%s|%s|%s" % (result.get("benchmark", ""), name, value,
                    primary.get("score"), primary.get("scoreUnit", ""), alloc.get("score"), alloc.get("scoreUnit", "")))
            """;

    /** shade 构建的执行结果。 */
    private static ProcessResult packageResult;

    /** 缩短后 JMH 运行的执行结果。 */
    private static ProcessResult benchmarkRunResult;

    /**
     * shade 出可执行基准 jar，并以缩短的迭代参数真实运行本次 task 的基准类一次。
     *
     * @throws IOException 结果目录无法创建
     */
    @BeforeAll
    static void buildShadedJarAndRunBenchmark() throws IOException {
        Files.createDirectories(RESULT_DIRECTORY);
        packageResult = runCommand(List.of("mvn", "-B", "-o", "-DskipTests", "-pl", "change-tracking-benchmark",
                "-am", "-Pbench", "package"), REPOSITORY_DIRECTORY, PACKAGE_TIMEOUT_SECONDS);
        final List<String> command = new ArrayList<>(
                List.of("java", "-jar", BENCHMARK_JAR.toString(), BENCHMARK_SELECTOR));
        command.addAll(JMH_OPTIONS);
        benchmarkRunResult = runCommand(command, MODULE_DIRECTORY, RUN_TIMEOUT_SECONDS);
    }

    @Test
    @Order(1)
    @DisplayName("shade 后经真实 JMH 运行应产出 10 条齐备条目，档位一致且时间与分配均为正")
    void shadedJmhRun_shouldProduceTenCompleteEntriesWithPositiveMetrics() {
        assertThat(packageResult.exitCode())
                .as("shade build exit code, output tail: %s", tail(packageResult.output()))
                .isZero();
        assertThat(benchmarkRunResult.exitCode())
                .as("jmh run exit code, output tail: %s", tail(benchmarkRunResult.output()))
                .isZero();
        assertThat(Files.exists(BENCHMARK_JAR)).as("shaded benchmark jar").isTrue();
        assertThat(Files.exists(JMH_RESULT_FILE)).as("jmh result json").isTrue();

        final List<Entry> entries = parsedEntries();

        assertThat(entries).as("result entries of the scanned benchmark").hasSize(EXPECTED_ENTRY_COUNT);
        for (final Map.Entry<String, List<Integer>> frozen : FROZEN_LEVELS_BY_METHOD.entrySet()) {
            final String method = frozen.getKey();
            final List<Entry> owned = entriesOf(entries, method);

            assertThat(owned).as("entries of %s", method).hasSize(frozen.getValue().size());
            assertThat(owned.stream().map(Entry::dimension).distinct())
                    .as("scanned dimension of %s", method)
                    .containsExactly(SCANNED_DIMENSION_BY_METHOD.get(method));
            assertThat(owned.stream().map(Entry::level).toList())
                    .as("scanned levels of %s", method)
                    .containsExactlyInAnyOrderElementsOf(frozen.getValue());
        }
        assertThat(entries).allSatisfy(entry -> {
            assertThat(entry.timeUnit()).as("time unit of %s", entry.benchmark()).isEqualTo(TIME_UNIT);
            assertThat(entry.time()).as("time per operation of %s", entry.benchmark()).isPositive();
            assertThat(entry.allocUnit()).as("allocation unit of %s", entry.benchmark()).isEqualTo(ALLOCATION_UNIT);
            assertThat(entry.allocRate()).as("allocation per operation of %s", entry.benchmark()).isPositive();
        });
    }

    @Test
    @Order(2)
    @DisplayName("shade 后 SPI 仍可发现，且 10 个档位组合均可经样本族产出对应规模样本")
    void shadedJar_shouldKeepSpiDiscoverableAndCreateEveryScannedSample() throws IOException {
        assertShadedSpiIsDiscoverable();

        final List<SampleShape> shapes = scannedShapes();
        assertThat(shapes).as("scanned shape combinations").hasSize(EXPECTED_ENTRY_COUNT);

        for (final SampleShape shape : shapes) {
            final Object sample = SampleFamily.create(shape);

            assertThat(sample).as("sample of %s", shape).isNotNull();
            assertThat(sample.getClass())
                    .as("sample type of %s", shape)
                    .isEqualTo(shape.fieldCount() == SampleShape.SUPPORTED_FIELD_COUNT_LOW
                            ? SampleOrderSummary.class
                            : SampleOrder.class);
            assertThat(itemsOf(sample)).as("item count of %s", shape).hasSize(shape.collectionSize());
        }
    }

    @Test
    @Order(3)
    @DisplayName("集合规模档位效应可观测：1000 档位的时间与分配均不低于 10 档位的 3 倍")
    void collectionSizeScan_shouldShowTheLevelEffectInTimeAndAllocation() {
        final List<Entry> entries = parsedEntries();
        final Entry small = entryOf(entries, COLLECTION_SIZE_METHOD, SMALL_COLLECTION_SIZE);
        final Entry large = entryOf(entries, COLLECTION_SIZE_METHOD, LARGE_COLLECTION_SIZE);

        assertThat(large.time())
                .as("time per operation of collection size %d over %d", LARGE_COLLECTION_SIZE, SMALL_COLLECTION_SIZE)
                .isGreaterThanOrEqualTo(LEVEL_EFFECT_FACTOR * small.time());
        assertThat(large.allocRate())
                .as("allocation per operation of collection size %d over %d", LARGE_COLLECTION_SIZE,
                        SMALL_COLLECTION_SIZE)
                .isGreaterThanOrEqualTo(LEVEL_EFFECT_FACTOR * small.allocRate());
    }

    /**
     * 校验 shade 后的 jar 仍暴露 core 的 SPI 提供者，并携带 JMH 生成的基准清单。
     *
     * @throws IOException 读取 jar 失败
     */
    private static void assertShadedSpiIsDiscoverable() throws IOException {
        try (JarFile jar = new JarFile(BENCHMARK_JAR.toFile())) {
            final JarEntry serviceEntry = jar.getJarEntry(SPI_SERVICE_ENTRY);

            assertThat(serviceEntry).as("shaded spi service entry %s", SPI_SERVICE_ENTRY).isNotNull();
            assertThat(new String(jar.getInputStream(serviceEntry).readAllBytes(), StandardCharsets.UTF_8))
                    .as("spi providers of the shaded jar")
                    .contains(CORE_PROVIDER);
            assertThat(jar.getJarEntry(BENCHMARK_LIST_ENTRY)).as("jmh generated benchmark list").isNotNull();
        }
    }

    /**
     * 逐个档位地构建全部 10 个扫描形状，形状由被测基准类的 {@code shape()} 产出。
     *
     * @return 10 个档位组合对应的样本形状
     */
    private static List<SampleShape> scannedShapes() {
        final List<SampleShape> shapes = new ArrayList<>();
        for (final int level : FROZEN_LEVELS_BY_METHOD.get(FIELD_COUNT_METHOD)) {
            final SnapshotBuildBenchmark.FieldCountScan scan = new SnapshotBuildBenchmark.FieldCountScan();
            scan.fieldCount = level;
            shapes.add(scan.shape());
        }
        for (final int level : FROZEN_LEVELS_BY_METHOD.get(NESTING_DEPTH_METHOD)) {
            final SnapshotBuildBenchmark.NestingDepthScan scan = new SnapshotBuildBenchmark.NestingDepthScan();
            scan.nestingDepth = level;
            shapes.add(scan.shape());
        }
        for (final int level : FROZEN_LEVELS_BY_METHOD.get(COLLECTION_SIZE_METHOD)) {
            final SnapshotBuildBenchmark.CollectionSizeScan scan = new SnapshotBuildBenchmark.CollectionSizeScan();
            scan.collectionSize = level;
            shapes.add(scan.shape());
        }
        return shapes;
    }

    /**
     * 读取样本根的商品项集合。
     *
     * @param sample 样本族产出的样本根
     * @return 商品项集合
     * @throws IllegalStateException 样本类型不受支持
     */
    private static List<?> itemsOf(final Object sample) {
        if (sample instanceof SampleOrder order) {
            return order.items();
        }
        if (sample instanceof SampleOrderSummary summary) {
            return summary.items();
        }
        throw new IllegalStateException("Unsupported sample type: " + sample.getClass().getName());
    }

    /**
     * 以 python3 严格解析 JMH 结果 JSON，并把每条条目转成 {@link Entry}。
     *
     * @return 严格解析出的结果条目
     */
    private static List<Entry> parsedEntries() {
        final ProcessResult parse = runPython(JMH_PARSE_SCRIPT, JMH_RESULT_FILE);

        assertThat(parse.exitCode())
                .as("strict json parse of %s: %s", JMH_RESULT_FILE, parse.output())
                .isZero();
        final List<Entry> entries = new ArrayList<>();
        for (final String value : valuesOf(parse.output(), ENTRY_PREFIX)) {
            entries.add(Entry.parse(value));
        }
        return entries;
    }

    /**
     * 取指定测量方法的全部结果条目。
     *
     * @param entries 全部结果条目
     * @param method  测量方法名
     * @return 该方法的结果条目
     */
    private static List<Entry> entriesOf(final List<Entry> entries, final String method) {
        return entries.stream()
                .filter(entry -> entry.benchmark().equals(SnapshotBuildBenchmark.class.getName() + "." + method))
                .toList();
    }

    /**
     * 取指定测量方法与档位的唯一结果条目。
     *
     * @param entries 全部结果条目
     * @param method  测量方法名
     * @param level   扫描档位
     * @return 该档位的结果条目
     */
    private static Entry entryOf(final List<Entry> entries, final String method, final int level) {
        final List<Entry> matching = entriesOf(entries, method).stream()
                .filter(entry -> entry.level() == level)
                .toList();

        assertThat(matching).as("entry of %s level %d", method, level).hasSize(1);
        return matching.getFirst();
    }

    /**
     * 以 python3 严格解析 JSON 并输出条目行。
     *
     * @param script   内联 python 脚本
     * @param jsonFile 待解析的 JSON 文件
     * @return 命令执行结果
     */
    private static ProcessResult runPython(final String script, final Path jsonFile) {
        return runCommand(List.of("python3", "-c", script, jsonFile.toString()), MODULE_DIRECTORY, 120);
    }

    /**
     * 在指定目录执行外部命令，并把合并后的输出落盘后读回。
     *
     * @param command          命令与参数
     * @param workingDirectory 工作目录
     * @param timeoutSeconds   超时秒数
     * @return 退出码与合并输出
     */
    private static ProcessResult runCommand(final List<String> command, final Path workingDirectory,
                                            final long timeoutSeconds) {
        final Path logFile;
        try {
            logFile = Files.createTempFile("snapshot-scan-integration-", ".log");
            logFile.toFile().deleteOnExit();
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to create the command log file", e);
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
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to run " + command, e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running " + command, e);
        }
    }

    /**
     * 取出输出中带指定前缀的行值。
     *
     * @param output 命令输出
     * @param prefix 行前缀
     * @return 去前缀后的行值列表
     */
    private static List<String> valuesOf(final String output, final String prefix) {
        return output.lines()
                .filter(line -> line.startsWith(prefix))
                .map(line -> line.substring(prefix.length()))
                .toList();
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
     * 一条 JMH 结果条目。
     *
     * @param benchmark 测量方法的全限定名
     * @param dimension 扫描维的 {@code @Param} 字段名
     * @param level     扫描档位
     * @param time      每次操作的平均时间
     * @param timeUnit  时间指标单位
     * @param allocRate 每次操作的分配字节数
     * @param allocUnit 分配指标单位
     */
    private record Entry(String benchmark, String dimension, int level, double time, String timeUnit,
                         double allocRate, String allocUnit) {

        /**
         * 解析严格 JSON 脚本输出的条目行。
         *
         * @param value 去前缀后的条目行
         * @return 解析出的结果条目
         * @throws IllegalStateException 行结构不符合脚本约定
         */
        static Entry parse(final String value) {
            final String[] fields = value.split("\\|", -1);
            if (fields.length != 6) {
                throw new IllegalStateException("Malformed entry line: " + value);
            }
            final String[] parameter = fields[1].split("=", 2);
            if (parameter.length != 2) {
                throw new IllegalStateException("Malformed entry parameter: " + value);
            }
            return new Entry(fields[0], parameter[0], Integer.parseInt(parameter[1]), Double.parseDouble(fields[2]),
                    fields[3], Double.parseDouble(fields[4]), fields[5]);
        }
    }

    /**
     * 外部命令的执行结果。
     *
     * @param exitCode 退出码，超时为 -1
     * @param output   合并后的标准输出与标准错误
     */
    private record ProcessResult(int exitCode, String output) {
    }
}