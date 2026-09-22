package com.nona.changeTracking.bench;

import com.nona.changeTracking.bench.env.EnvironmentRecord;
import com.nona.changeTracking.bench.env.EnvironmentRecordCollector;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 基准模块的装配面集成测试：验证单元测试用替身覆盖不到的真实链路。
 * <p>
 * 覆盖：一条命令载体端到端跑通并产出两份 JSON、{@code -prof gc} 的分配字节数、
 * 环境记录四项与真实 JVM 对应、shade 后 SPI 仍可被 ServiceLoader 发现、
 * 默认 {@code mvn test} 不执行基准、模块不进入依赖面、真实探针可填充全部环境事实。
 * <p>
 * 该类由 surefire 默认排除（{@code **}{@code /}{@code *IntegrationTest}），
 * 仅在 {@code -Pfull} 全量执行时运行；执行入口为模块内的 {@code bench.sh}。
 */
@DisplayName("BenchmarkModule 装配面集成测试")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BenchmarkModuleIntegrationTest {

    /** 本模块目录，surefire 的工作目录。 */
    private static final Path MODULE_DIRECTORY = Path.of("").toAbsolutePath();

    /** 仓库根目录，命令载体内部的 Maven 调用在此执行。 */
    private static final Path REPOSITORY_DIRECTORY = MODULE_DIRECTORY.getParent();

    /** shade 产出的可执行基准 jar。 */
    private static final Path BENCHMARK_JAR = MODULE_DIRECTORY.resolve("target").resolve("benchmarks.jar");

    /** 结果目录，与 {@code bench.sh} 的 {@code -rff} 参数一致。 */
    private static final Path RESULT_DIRECTORY = MODULE_DIRECTORY.resolve("target").resolve("benchmark-results");

    /** JMH 原生 JSON 结果文件。 */
    private static final Path JMH_RESULT_FILE = RESULT_DIRECTORY.resolve("jmh-result.json");

    /** 环境记录文件。 */
    private static final Path ENVIRONMENT_RECORD_FILE = RESULT_DIRECTORY.resolve("environment.json");

    /** 解析并校验 JMH 结果 JSON 的脚本（严格 {@code json.load}，非子串断言）。 */
    private static final String JMH_PARSE_SCRIPT = """
            import json, sys
            with open(sys.argv[1], encoding="utf-8") as handle:
                data = json.load(handle)
            assert isinstance(data, list) and len(data) >= 1, "no benchmark result entries"
            entry = data[0]
            secondary = entry.get("secondaryMetrics") or {}
            metric = secondary.get("gc.alloc.rate.norm")
            assert metric is not None, "gc.alloc.rate.norm missing"
            print("entries=%d" % len(data))
            print("benchmark=%s" % entry.get("benchmark", ""))
            print("allocRateNormScore=%s" % metric.get("score"))
            print("allocRateNormUnit=%s" % metric.get("scoreUnit", ""))
            """;

    /** 解析并校验环境记录 JSON 的脚本（严格 {@code json.load}）。 */
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

    /** 一条命令载体的执行结果，所有用例共享这一次真实装配。 */
    private static ProcessResult benchCommandResult;

    /**
     * 执行模块内的一条命令载体 {@code bench.sh}：内部先以 {@code -Pbench package} 产出
     * {@code target/benchmarks.jar}，再以 JMH 运行并把结果与环境记录写入结果目录。
     */
    @BeforeAll
    static void runBenchmarkCommand() {
        benchCommandResult = runCommand(List.of("bash", "bench.sh"), MODULE_DIRECTORY, 900);
    }

    @Test
    @Order(1)
    @DisplayName("一条命令应跑通并产出可严格解析的 JMH 结果与环境记录")
    void benchCommand_shouldProduceParseableResults() {
        assertThat(benchCommandResult.exitCode())
                .as("bench.sh exit code, output tail: %s", tail(benchCommandResult.output()))
                .isZero();
        assertThat(Files.exists(BENCHMARK_JAR)).as("shaded benchmark jar").isTrue();
        assertThat(Files.exists(JMH_RESULT_FILE)).as("jmh result json").isTrue();
        assertThat(Files.exists(ENVIRONMENT_RECORD_FILE)).as("environment record json").isTrue();

        final ProcessResult jmhParse = runPython(JMH_PARSE_SCRIPT, JMH_RESULT_FILE);
        assertThat(jmhParse.exitCode()).as("strict json parse of jmh-result.json: %s", jmhParse.output()).isZero();
        assertThat(valuesOf(jmhParse.output(), "entries=")).containsExactly("1");
        assertThat(valuesOf(jmhParse.output(), "benchmark="))
                .containsExactly("com.nona.changeTracking.bench.ModuleSmokeBenchmark.trackAndDiffMinimalSample");

        final ProcessResult environmentParse = runPython(ENVIRONMENT_PARSE_SCRIPT, ENVIRONMENT_RECORD_FILE);
        assertThat(environmentParse.exitCode())
                .as("strict json parse of environment.json: %s", environmentParse.output())
                .isZero();
        assertThat(valuesOf(environmentParse.output(), "jdkVersionField=")).hasSize(1);
    }

    @Test
    @Order(2)
    @DisplayName("-prof gc 应真实产出每次操作的分配字节数")
    void gcProfiler_shouldReportAllocationBytesPerOperation() {
        final ProcessResult jmhParse = runPython(JMH_PARSE_SCRIPT, JMH_RESULT_FILE);

        assertThat(valuesOf(jmhParse.output(), "allocRateNormUnit=")).containsExactly("B/op");
        final List<String> scores = valuesOf(jmhParse.output(), "allocRateNormScore=");
        assertThat(scores).hasSize(1);
        assertThat(Double.parseDouble(scores.get(0))).isPositive();
    }

    @Test
    @Order(3)
    @DisplayName("环境记录四项应与真实 JVM 对应")
    void environmentRecord_shouldMatchRunningJvm() {
        final ProcessResult environmentParse = runPython(ENVIRONMENT_PARSE_SCRIPT, ENVIRONMENT_RECORD_FILE);

        assertThat(valuesOf(environmentParse.output(), "jdkVersionField="))
                .containsExactly(System.getProperty("java.version"));

        final List<String> jvmArguments = valuesOf(environmentParse.output(), "jvmArgument=");
        assertThat(jvmArguments).isNotEmpty();
        assertThat(jvmArguments).allSatisfy(argument -> assertThat(argument).startsWith("-"));

        final List<String> collectors = valuesOf(environmentParse.output(), "gcCollector=");
        assertThat(collectors).isNotEmpty();
        assertThat(collectors).isSubsetOf(runningGcCollectorNames());

        assertThat(valuesOf(environmentParse.output(), "hostNameField="))
                .singleElement()
                .satisfies(hostName -> assertThat(hostName).isNotBlank());
        assertThat(valuesOf(environmentParse.output(), "availableProcessorsField="))
                .singleElement()
                .satisfies(processors -> assertThat(Integer.parseInt(processors)).isGreaterThanOrEqualTo(1));
        assertThat(valuesOf(environmentParse.output(), "maxMemoryBytesField="))
                .singleElement()
                .satisfies(memory -> assertThat(Long.parseLong(memory)).isPositive());
    }

    @Test
    @Order(4)
    @DisplayName("shade 后 SPI 仍可被发现，且基准 setUp 装配成功")
    void shadedJar_shouldKeepSpiProviderDiscoverable() throws IOException {
        final ProcessResult jarListing = runCommand(List.of("jar", "tf", "target/benchmarks.jar"), MODULE_DIRECTORY, 120);

        assertThat(jarListing.exitCode()).isZero();
        assertThat(jarListing.output().lines().map(String::trim).toList())
                .contains("META-INF/services/com.nona.changeTracking.spi.TrackingCapabilityProvider")
                .contains("META-INF/BenchmarkList");
        assertThat(serviceProviderContent())
                .contains("com.nona.changeTracking.internal.capability.DefaultTrackingCapabilityProvider");
        assertThat(benchCommandResult.exitCode())
                .as("benchmark setUp assembles the tracker through the discovered provider")
                .isZero();
    }

    @Test
    @Order(5)
    @DisplayName("默认 mvn test 不应执行基准")
    void defaultTestRun_shouldNotExecuteBenchmarks() {
        final Map<String, String> resultsBefore = resultSnapshot();

        final ProcessResult testRun = runCommand(
                List.of("mvn", "-B", "-pl", "change-tracking-benchmark", "-am", "test"), REPOSITORY_DIRECTORY, 900);

        assertThat(testRun.exitCode()).as("default mvn test exit code").isZero();
        assertThat(testRun.output()).doesNotContain("# Run progress");
        assertThat(testRun.output()).doesNotContain("# Fork:");
        assertThat(testRun.output()).doesNotContain("# Benchmark mode");
        assertThat(resultSnapshot()).isEqualTo(resultsBefore);
    }

    @Test
    @Order(6)
    @DisplayName("模块不 deploy、不进入库依赖面")
    void module_shouldStayOutsideDependencySurface() throws IOException {
        final ProcessResult effectivePom = runCommand(List.of("mvn", "-B", "-pl", "change-tracking-benchmark",
                "org.apache.maven.plugins:maven-help-plugin:3.5.2:effective-pom"), REPOSITORY_DIRECTORY, 300);

        assertThat(effectivePom.exitCode()).isZero();
        assertThat(effectivePom.output()).doesNotContain("<distributionManagement>");
        assertThat(Files.readString(REPOSITORY_DIRECTORY.resolve("change-tracking-api").resolve("pom.xml")))
                .doesNotContain("change-tracking-benchmark");
        assertThat(Files.readString(REPOSITORY_DIRECTORY.resolve("change-tracking-core").resolve("pom.xml")))
                .doesNotContain("change-tracking-benchmark");
    }

    @Test
    @Order(7)
    @DisplayName("真实探针应填充全部环境事实")
    void realProbe_shouldCaptureEveryEnvironmentFact() {
        final EnvironmentRecord record = EnvironmentRecordCollector.capture();

        assertThat(record.jdkVersion()).isEqualTo(System.getProperty("java.version"));
        assertThat(record.jvmArguments()).isNotNull();
        assertThat(record.gcCollectors()).isNotEmpty();
        assertThat(record.gcCollectors()).isSubsetOf(runningGcCollectorNames());
        assertThat(record.machine().hostName()).isNotBlank();
        assertThat(record.machine().availableProcessors()).isGreaterThanOrEqualTo(1);
        assertThat(record.machine().maxMemoryBytes()).isPositive();
    }

    /**
     * 读取 shade 后 jar 内 core 的 SPI 服务文件内容。
     *
     * @return 服务文件文本
     * @throws IOException 读取 jar 失败
     */
    private static String serviceProviderContent() throws IOException {
        try (JarFile jar = new JarFile(BENCHMARK_JAR.toFile())) {
            final var entry = jar.getJarEntry("META-INF/services/com.nona.changeTracking.spi.TrackingCapabilityProvider");
            return new String(jar.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * 返回当前 JVM 的 GC 收集器名集合，作为 fork JVM 收集器名的对照基准。
     *
     * @return 当前 JVM 的 GC 收集器名
     */
    private static Set<String> runningGcCollectorNames() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .map(GarbageCollectorMXBean::getName)
                .collect(Collectors.toSet());
    }

    /**
     * 采集结果目录的文件名与修改时间快照，用于判断默认测试是否触碰了基准产物。
     *
     * @return 文件名到 {@code 修改时间:大小} 的映射；目录不存在时为空映射
     */
    private static Map<String, String> resultSnapshot() {
        if (!Files.isDirectory(RESULT_DIRECTORY)) {
            return Map.of();
        }
        try (Stream<Path> paths = Files.walk(RESULT_DIRECTORY)) {
            return paths.filter(Files::isRegularFile)
                    .collect(Collectors.toMap(
                            path -> RESULT_DIRECTORY.relativize(path).toString(),
                            path -> {
                                try {
                                    return Files.getLastModifiedTime(path).toMillis() + ":" + Files.size(path);
                                } catch (final IOException e) {
                                    throw new UncheckedIOException(e);
                                }
                            }));
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 以 python3 严格解析 JSON 并输出提取行。
     *
     * @param script    内联 python 脚本
     * @param jsonFile  待解析的 JSON 文件
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
    private static ProcessResult runCommand(final List<String> command, final Path workingDirectory, final long timeoutSeconds) {
        final Path logFile;
        try {
            logFile = Files.createTempFile("benchmark-integration-", ".log");
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
     * 外部命令的执行结果。
     *
     * @param exitCode 退出码，超时为 -1
     * @param output   合并后的标准输出与标准错误
     */
    private record ProcessResult(int exitCode, String output) {
    }
}