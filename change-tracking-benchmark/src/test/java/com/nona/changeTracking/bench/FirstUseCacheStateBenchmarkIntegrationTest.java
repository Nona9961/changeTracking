package com.nona.changeTracking.bench;

import com.nona.changeTracking.bench.result.BenchmarkResultComparator;
import com.nona.changeTracking.bench.result.ColdSampleTable;
import com.nona.changeTracking.bench.result.JmhResultJsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 冷态首次类型使用载体的装配面集成测试：在 shade 后的 {@code benchmarks.jar} 上真实运行冻结的冷态协议，
 * 断言单次模式、零预热、单迭代 batch size 1 与多 fork 在真实 JMH 运行中生效，每 fork 追加一行带两项指标
 * 的冷态原始样本，附加的环境记录与样本族新形态在 shade 后可用，以及冷态原始采样表不得进入稳态比较器。
 * <p>
 * 该类由 surefire 默认排除（{@code **}{@code /}{@code *IntegrationTest}），仅在 {@code -Pfull} 全量执行时
 * 运行；shade 构建与 JMH 命令由 {@link BenchmarkRunFixture} 在三个装配面测试类之间共享一次。
 */
@DisplayName("FirstUseCacheStateBenchmark 装配面集成测试")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FirstUseCacheStateBenchmarkIntegrationTest {

    /** 冷态 JMH 结果的严格解析脚本：注解组合与两项指标在此断言。 */
    private static final String COLD_JSON_PARSE_SCRIPT = """
            import json, sys
            with open(sys.argv[1], encoding="utf-8") as handle:
                data = json.load(handle)
            assert isinstance(data, list), "result json is not a list: %r" % type(data)
            assert len(data) == 2, "expected 2 cold entries, got %d" % len(data)
            for result in data:
                primary = result.get("primaryMetric") or {}
                alloc = (result.get("secondaryMetrics") or {}).get("gc.alloc.rate.norm") or {}
                print("ENTRY|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s" % (
                    result.get("benchmark", ""),
                    result.get("mode", ""),
                    result.get("forks", ""),
                    result.get("warmupIterations", ""),
                    result.get("measurementIterations", ""),
                    result.get("measurementBatchSize", ""),
                    len(result.get("params") or {}),
                    primary.get("score"),
                    primary.get("scoreUnit", ""),
                    alloc.get("score"),
                    alloc.get("scoreUnit", "")))
            """;

    /** 环境记录的严格解析脚本：四项事实与列表在此输出。 */
    private static final String ENVIRONMENT_PARSE_SCRIPT = """
            import json, sys
            with open(sys.argv[1], encoding="utf-8") as handle:
                data = json.load(handle)
            print("jdkVersionField=%s" % data["jdkVersion"])
            print("hostNameField=%s" % data["machine"]["hostName"])
            print("availableProcessorsField=%s" % data["machine"]["availableProcessors"])
            print("maxMemoryBytesField=%s" % data["machine"]["maxMemoryBytes"])
            for argument in data["jvmArguments"]:
                print("jvmArgument=%s" % argument)
            for collector in data["gcCollectors"]:
                print("gcCollector=%s" % collector)
            """;

    /** 样本族新形态探针：在 shade 后的 jar 类路径上执行深链形状与深层叶子变更。 */
    private static final String SAMPLE_SHAPE_PROBE_SCRIPT = """
            var shape = com.nona.changeTracking.bench.sample.SampleShape.deepChain();
            System.out.println("SHAPE|" + shape.fieldCount() + "|" + shape.nestingDepth() + "|" + shape.collectionSize());
            var sample = com.nona.changeTracking.bench.sample.SampleFamily.create(shape);
            com.nona.changeTracking.bench.sample.SampleMutator.changeDeepestLeafField(sample);
            System.out.println("MUTATED|" + sample.getClass().getName());
            /exit
            """;

    /** 样本族新形态探针的类路径条目。 */
    private static final String SAMPLE_CLASS_ENTRY_PREFIX = "com/nona/changeTracking/bench/sample/";

    /** 冷态运行开始前原始采样表已有的行数，用于隔离本次运行追加的行。 */
    private static int rowsBeforeColdRun;

    /**
     * 记录原始采样表当前行数并真实执行冷态协议一次；用例断言的是本次运行追加的行。
     */
    @BeforeAll
    static void runColdProtocolOnTheShadedJar() {
        rowsBeforeColdRun = existingColdRows();
        BenchmarkRunFixture.coldRun();
    }

    @Test
    @Order(1)
    @DisplayName("shade 后真实多 fork 运行应产出两条完整冷态条目（单次模式、零预热、单迭代、batch size 1）")
    void shadedColdRun_shouldProduceTwoCompleteSingleShotEntries() {
        assertThat(BenchmarkRunFixture.shadedJar().exitCode())
                .as("shade build exit code, output tail: %s",
                        BenchmarkRunFixture.tail(BenchmarkRunFixture.shadedJar().output()))
                .isZero();
        assertThat(BenchmarkRunFixture.coldRun().exitCode())
                .as("cold jmh run exit code, output tail: %s",
                        BenchmarkRunFixture.tail(BenchmarkRunFixture.coldRun().output()))
                .isZero();
        assertThat(Files.exists(BenchmarkRunFixture.BENCHMARK_JAR)).as("shaded benchmark jar").isTrue();
        assertThat(Files.exists(BenchmarkRunFixture.COLD_RESULT_FILE)).as("cold jmh result json").isTrue();

        final BenchmarkRunFixture.ProcessResult parse =
                runPython(COLD_JSON_PARSE_SCRIPT, BenchmarkRunFixture.COLD_RESULT_FILE);

        assertThat(parse.exitCode())
                .as("strict json parse of %s: %s", BenchmarkRunFixture.COLD_RESULT_FILE, parse.output())
                .isZero();
        final List<ColdEntry> entries = BenchmarkRunFixture.valuesOf(parse.output(), "ENTRY|").stream()
                .map(ColdEntry::parse)
                .toList();
        assertThat(entries).hasSize(2);
        assertThat(entries).extracting(ColdEntry::benchmark)
                .containsExactlyInAnyOrder(
                        FirstUseCacheStateBenchmark.class.getName() + ".firstTargetStrategySnapshot",
                        FirstUseCacheStateBenchmark.class.getName() + ".firstFullCalculationAfterBaselineRestore");
        assertThat(entries).allSatisfy(entry -> {
            assertThat(entry.mode()).as("mode of %s", entry.benchmark()).isEqualTo("ss");
            assertThat(entry.forks()).as("forks of %s", entry.benchmark())
                    .isEqualTo(BenchmarkRunFixture.COLD_FORKS);
            assertThat(entry.warmupIterations()).as("warmup of %s", entry.benchmark()).isZero();
            assertThat(entry.measurementIterations()).as("measurement of %s", entry.benchmark()).isEqualTo(1);
            assertThat(entry.measurementBatchSize()).as("batch size of %s", entry.benchmark()).isEqualTo(1);
            assertThat(entry.paramsCount()).as("params of %s", entry.benchmark()).isZero();
            assertThat(entry.time()).as("time of %s", entry.benchmark()).isPositive();
            assertThat(entry.timeUnit()).as("time unit of %s", entry.benchmark()).isEqualTo("ns/op");
            assertThat(entry.alloc()).as("allocation of %s", entry.benchmark()).isPositive();
            assertThat(entry.allocUnit()).as("allocation unit of %s", entry.benchmark()).isEqualTo("B/op");
        });
    }

    @Test
    @Order(2)
    @DisplayName("每 fork 一行冷态原始样本：方法与 fork 数之积、两项指标齐备且为正、计量开销单列")
    void coldRawSamples_shouldCarryOneCompleteRowPerMethodAndFork() {
        assertThat(Files.exists(BenchmarkRunFixture.COLD_SAMPLE_TABLE)).as("raw sampling table").isTrue();

        final List<ColdSampleTable.ColdSample> rows = ColdSampleTable.read(BenchmarkRunFixture.COLD_SAMPLE_TABLE);
        assertThat(rows.size()).as("rows before this run must not exceed the current table").isGreaterThanOrEqualTo(
                rowsBeforeColdRun);
        final List<ColdSampleTable.ColdSample> appended = rows.subList(rowsBeforeColdRun, rows.size());

        assertThat(appended).as("one row per method and fork").hasSize(2 * BenchmarkRunFixture.COLD_FORKS);
        assertThat(appended).allSatisfy(row -> {
            assertThat(row.protocol()).isEqualTo(FirstUseCacheStateBenchmark.PROTOCOL);
            assertThat(row.params()).isEmpty();
            assertThat(row.nanosPerOperation()).isPositive();
            assertThat(row.allocatedBytesPerOperation()).isPositive();
            assertThat(row.meteringOverheadNanos()).isNotNegative();
        });
        assertThat(appended)
                .filteredOn(row -> row.benchmark()
                        .endsWith(FirstUseCacheStateBenchmark.class.getSimpleName() + ".firstTargetStrategySnapshot"))
                .hasSize(BenchmarkRunFixture.COLD_FORKS);
        assertThat(appended)
                .filteredOn(row -> row.benchmark().endsWith(
                        FirstUseCacheStateBenchmark.class.getSimpleName() + ".firstFullCalculationAfterBaselineRestore"))
                .hasSize(BenchmarkRunFixture.COLD_FORKS);
    }

    @Test
    @Order(3)
    @DisplayName("冷态原始采样表不得进入稳态比较器：JMH 结果读取与比较入口都应拒绝它")
    void coldTable_shouldStayOutsideTheSteadyStateComparison() {
        assertThatThrownBy(() -> JmhResultJsonParser.parse(BenchmarkRunFixture.COLD_SAMPLE_TABLE))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> BenchmarkResultComparator.compare(
                BenchmarkRunFixture.COLD_SAMPLE_TABLE, BenchmarkRunFixture.COLD_SAMPLE_TABLE))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> BenchmarkResultComparator.compare(
                BenchmarkRunFixture.COLD_SAMPLE_TABLE, BenchmarkRunFixture.COLD_RESULT_FILE))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @Order(4)
    @DisplayName("环境记录四项随冷态运行归档，样本族新形态在 shade 后可用")
    void environmentRecordAndShadedSampleShapes_shouldBeAvailable() throws IOException {
        assertEnvironmentRecordMatchesTheRunningJvm();
        assertShadedJarCarriesTheSampleFamily();
        assertFrozenShapeProbeRunsOnTheShadedJar();
    }

    /**
     * 断言冷态 trial 装配归档的环境记录四项与真实 JVM 对应。
     */
    private static void assertEnvironmentRecordMatchesTheRunningJvm() {
        assertThat(Files.exists(BenchmarkRunFixture.ENVIRONMENT_RECORD)).as("environment record json").isTrue();

        final BenchmarkRunFixture.ProcessResult parse =
                runPython(ENVIRONMENT_PARSE_SCRIPT, BenchmarkRunFixture.ENVIRONMENT_RECORD);

        assertThat(parse.exitCode())
                .as("strict json parse of %s: %s", BenchmarkRunFixture.ENVIRONMENT_RECORD, parse.output())
                .isZero();
        assertThat(BenchmarkRunFixture.valuesOf(parse.output(), "jdkVersionField="))
                .containsExactly(System.getProperty("java.version"));
        assertThat(BenchmarkRunFixture.valuesOf(parse.output(), "jvmArgument="))
                .allSatisfy(argument -> assertThat(argument).startsWith("-"));
        assertThat(BenchmarkRunFixture.valuesOf(parse.output(), "gcCollector="))
                .isNotEmpty()
                .isSubsetOf(runningGcCollectorNames());
        assertThat(BenchmarkRunFixture.valuesOf(parse.output(), "hostNameField="))
                .singleElement()
                .satisfies(hostName -> assertThat(hostName).isNotBlank());
        assertThat(BenchmarkRunFixture.valuesOf(parse.output(), "availableProcessorsField="))
                .singleElement()
                .satisfies(processors -> assertThat(Integer.parseInt(processors)).isGreaterThanOrEqualTo(1));
        assertThat(BenchmarkRunFixture.valuesOf(parse.output(), "maxMemoryBytesField="))
                .singleElement()
                .satisfies(memory -> assertThat(Long.parseLong(memory)).isPositive());
    }

    /**
     * 断言 shade 后的 jar 携带样本族新形态所在包。
     *
     * @throws IOException 读取 jar 失败
     */
    private static void assertShadedJarCarriesTheSampleFamily() throws IOException {
        final BenchmarkRunFixture.ProcessResult listing = BenchmarkRunFixture.run(
                List.of("jar", "tf", BenchmarkRunFixture.BENCHMARK_JAR.toString()),
                BenchmarkRunFixture.MODULE_DIRECTORY, 120);

        assertThat(listing.exitCode()).as("jar listing exit code").isZero();
        assertThat(listing.output().lines().map(String::trim).toList())
                .contains(SAMPLE_CLASS_ENTRY_PREFIX + "SampleShape.class")
                .contains(SAMPLE_CLASS_ENTRY_PREFIX + "SampleMutator.class")
                .contains(SAMPLE_CLASS_ENTRY_PREFIX + "SampleFamily.class");
    }

    /**
     * 在 shade 后的 jar 类路径上执行样本族新形态探针：深链形状与深层叶子变更都必须可用。
     *
     * @throws IOException 探针脚本或临时目录无法创建
     */
    private static void assertFrozenShapeProbeRunsOnTheShadedJar() throws IOException {
        final Path probeScript = Files.createTempFile("sample-shape-probe-", ".jsh");
        final Path preferencesRoot = Files.createTempDirectory("sample-shape-probe-prefs-");
        probeScript.toFile().deleteOnExit();
        Files.writeString(probeScript, SAMPLE_SHAPE_PROBE_SCRIPT, StandardCharsets.UTF_8);

        final BenchmarkRunFixture.ProcessResult probe = BenchmarkRunFixture.run(
                List.of("jshell", "--class-path", BenchmarkRunFixture.BENCHMARK_JAR.toString(),
                        "--feedback", "silent", "-J-Djava.util.prefs.userRoot=" + preferencesRoot,
                        probeScript.toString()),
                BenchmarkRunFixture.MODULE_DIRECTORY, 300);

        assertThat(probe.output().lines().map(String::trim).toList())
                .as("shaded sample family probe output, tail: %s", BenchmarkRunFixture.tail(probe.output()))
                .contains("SHAPE|20|32|100")
                .anyMatch(line -> line.startsWith("MUTATED|"));
    }

    /**
     * 以 python3 严格解析 JSON 并输出提取行。
     *
     * @param script   内联 python 脚本
     * @param jsonFile  待解析的 JSON 文件
     * @return 命令执行结果
     */
    private static BenchmarkRunFixture.ProcessResult runPython(final String script, final Path jsonFile) {
        return BenchmarkRunFixture.run(List.of("python3", "-c", script, jsonFile.toString()),
                BenchmarkRunFixture.MODULE_DIRECTORY, 120);
    }

    /**
     * 返回当前原始采样表的行数；文件不存在时视为 0 行。
     *
     * @return 原始采样表当前的行数
     */
    private static int existingColdRows() {
        if (!Files.exists(BenchmarkRunFixture.COLD_SAMPLE_TABLE)) {
            return 0;
        }
        return ColdSampleTable.read(BenchmarkRunFixture.COLD_SAMPLE_TABLE).size();
    }

    /**
     * 返回当前 JVM 的 GC 收集器名集合。
     *
     * @return 当前 JVM 的 GC 收集器名
     */
    private static Set<String> runningGcCollectorNames() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .map(GarbageCollectorMXBean::getName)
                .collect(Collectors.toSet());
    }

    /**
     * 一条冷态 JMH 结果条目。
     *
     * @param benchmark             测量方法的全限定名
     * @param mode                  JMH 测量模式
     * @param forks                 fork 数
     * @param warmupIterations      预热迭代数
     * @param measurementIterations 测量迭代数
     * @param measurementBatchSize  测量批次大小
     * @param paramsCount           参数个数
     * @param time                  主指标分数
     * @param timeUnit              主指标单位
     * @param alloc                 分配指标分数
     * @param allocUnit             分配指标单位
     */
    private record ColdEntry(String benchmark,
                             String mode,
                             int forks,
                             int warmupIterations,
                             int measurementIterations,
                             int measurementBatchSize,
                             int paramsCount,
                             double time,
                             String timeUnit,
                             double alloc,
                             String allocUnit) {

        /**
         * 解析严格 JSON 脚本输出的条目行。
         *
         * @param value 去前缀后的条目行
         * @return 解析出的结果条目
         * @throws IllegalStateException 行结构不符合脚本约定
         */
        static ColdEntry parse(final String value) {
            final String[] fields = value.split("\\|", -1);
            if (fields.length != 11) {
                throw new IllegalStateException("Malformed cold entry line: " + value);
            }
            try {
                return new ColdEntry(fields[0], fields[1], Integer.parseInt(fields[2]), Integer.parseInt(fields[3]),
                        Integer.parseInt(fields[4]), Integer.parseInt(fields[5]), Integer.parseInt(fields[6]),
                        Double.parseDouble(fields[7]), fields[8], Double.parseDouble(fields[9]), fields[10]);
            } catch (final NumberFormatException e) {
                throw new IllegalStateException("Malformed cold entry line: " + value, e);
            }
        }
    }
}
