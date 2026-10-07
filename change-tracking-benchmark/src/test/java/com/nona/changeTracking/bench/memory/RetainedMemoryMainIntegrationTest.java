package com.nona.changeTracking.bench.memory;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 保留内存测量载体的装配面集成测试：在 shade 后的 {@code benchmarks.jar} 上真实运行命令行入口，验证
 * 单元测试用替身覆盖不到的两条装配面。
 * <p>
 * <b>A1 默认框架整链</b>：以子进程运行入口（无 {@code --scenario}，即对五场景各调一次
 * {@link RetainedMemoryMeasurement#measure(RetainedMemoryScenario)}），断言默认装配链
 * （{@link com.nona.changeTracking.api.ChangeTrackerFactory#builder()} → ServiceLoader → 反射快照 →
 * 默认比较策略 → {@code ChangeSet} → 两视图）在五场景各产出正的保留脚印，且重复获取相对仅计算的
 * 差值只是视图列表开销的量级。
 * <p>
 * <b>A2 真实反射遍历的真实模块访问</b>：同一入口在真实决策图上读 {@code java.lang}/{@code java.util}
 * 引用字段，命令必须带 {@code --add-opens java.base/java.lang=ALL-UNNAMED --add-opens
 * java.base/java.util=ALL-UNNAMED}；缺省时须响亮失败（非零退出、诊断指向无法访问的字段），不得静默跳过。
 * 稳定键行按固定键序严格解析。
 * <p>
 * A1 与 A2 都在子进程中运行：surefire 的 {@code argLine} 只 open {@code java.lang}，进程内测量真实结果图
 * 会命中未 open 的 {@code java.util}，因此真实模块访问只能由带 {@code --add-opens} 的子进程覆盖。
 * <p>
 * 该类由 surefire 默认排除（{@code **}{@code /}{@code *IntegrationTest}），仅在 {@code -Pfull} 全量执行时运行。
 */
@DisplayName("RetainedMemoryMain 装配面集成测试")
class RetainedMemoryMainIntegrationTest {

    /** 模块目录，surefire 测试 JVM 的工作目录。 */
    private static final Path MODULE_DIRECTORY = Path.of("").toAbsolutePath();

    /** 仓库根目录，shade 构建在此执行。 */
    private static final Path REPOSITORY_DIRECTORY = MODULE_DIRECTORY.getParent();

    /** shade 产出的可执行基准 jar。 */
    private static final Path BENCHMARK_JAR = MODULE_DIRECTORY.resolve("target").resolve("benchmarks.jar");

    /** 全覆盖场景子进程命令中固定的模块开放参数。 */
    private static final List<String> ADD_OPENS = List.of(
            "--add-opens", "java.base/java.lang=ALL-UNNAMED",
            "--add-opens", "java.base/java.util=ALL-UNNAMED");

    /** 稳定类行的严格格式：类名（无空白）加对象数与字节数。 */
    private static final Pattern CLASS_LINE =
            Pattern.compile("retainedClass=(\\S+) count=(\\d+) bytes=(\\d+)");

    /** 五场景命令行的声明顺序。 */
    private static final List<String> SCENARIO_ORDER = List.of(
            "calculateOnly", "leafOnly", "fullView", "repeatedAcquire", "calculateAndLeaf");

    /** 视图列表开销的上界比例：重复获取相对仅计算的字节增量须远小于基线，量级为列表包装。 */
    private static final long VIEW_OVERHEAD_BYTES_DIVISOR = 4;

    /** 视图列表开销的对象数上界：仅两次视图包装的少量对象。 */
    private static final int VIEW_OVERHEAD_OBJECT_LIMIT = 20;

    /** shade 构建超时秒数。 */
    private static final long PACKAGE_TIMEOUT_SECONDS = 900;

    /** 一次子进程测量超时秒数。 */
    private static final long RUN_TIMEOUT_SECONDS = 300;

    /** 带 {@code --add-opens} 的五场景运行结果。 */
    private static ProcessResult allScenariosRun;

    /** 缺省 {@code --add-opens} 的运行结果。 */
    private static ProcessResult withoutOpensRun;

    /** 只测量两个请求场景的运行结果。 */
    private static ProcessResult filteredRun;

    /**
     * 构建 shade jar 各一次，并真实运行入口：五场景（带 open）、缺省（无 open）、按请求过滤。
     */
    @BeforeAll
    static void runOnTheShadedJar() {
        buildShadedJar();
        allScenariosRun = runProbe(List.of(), ADD_OPENS);
        withoutOpensRun = runProbe(List.of(), List.of());
        filteredRun = runProbe(List.of("--scenario", "calculateOnly", "--scenario", "repeatedAcquire"), ADD_OPENS);
    }

    @Test
    @DisplayName("A1 默认框架整链：五场景各产出正的保留脚印，持有结果与场景一致")
    void defaultFrameworkChain_shouldMeasurePositiveFootprintForEveryScenario() {
        assertThat(allScenariosRun.exitCode())
                .as("full scenario run exit code, output: %s", failureDetail(allScenariosRun))
                .isZero();

        final List<ScenarioReport> reports = parseBlocks(allScenariosRun.stdout());

        assertThat(reports).extracting(ScenarioReport::scenario).containsExactlyElementsOf(SCENARIO_ORDER);
        assertThat(reports).allSatisfy(report -> {
            assertThat(report.shape()).as("shape of %s", report.scenario()).isEqualTo("deepChain");
            assertThat(report.retainedBytes()).as("retained bytes of %s", report.scenario()).isPositive();
            assertThat(report.retainedObjects()).as("retained objects of %s", report.scenario()).isPositive();
            assertThat(report.classes()).as("class aggregation of %s", report.scenario()).isNotEmpty();
        });

        assertThat(reportFor(reports, "calculateOnly").heldResults()).containsExactly("calculatedSet");
        assertThat(reportFor(reports, "leafOnly").heldResults()).containsExactly("calculatedSet", "leafView");
        assertThat(reportFor(reports, "fullView").heldResults()).containsExactly("calculatedSet", "fullView");
        assertThat(reportFor(reports, "repeatedAcquire").heldResults())
                .containsExactly("calculatedSet", "fullView", "fullView");
        assertThat(reportFor(reports, "calculateAndLeaf").heldResults())
                .containsExactly("calculatedSet", "fullView", "leafView");
    }

    @Test
    @DisplayName("A1 重复获取相对仅计算只增加视图列表开销")
    void repeatedAcquisition_shouldOnlyAddViewOverheadOverCalculateOnly() {
        final List<ScenarioReport> reports = parseBlocks(allScenariosRun.stdout());
        final ScenarioReport baseline = reportFor(reports, "calculateOnly");
        final ScenarioReport repeated = reportFor(reports, "repeatedAcquire");

        final long extraBytes = repeated.retainedBytes() - baseline.retainedBytes();
        final long extraObjects = repeated.retainedObjects() - baseline.retainedObjects();

        assertThat(extraBytes)
                .as("acquiring the complete view twice keeps the same result nodes and adds the view lists")
                .isPositive();
        assertThat(extraBytes)
                .as("view overhead is an order smaller than the calculated set baseline")
                .isLessThan(baseline.retainedBytes() / VIEW_OVERHEAD_BYTES_DIVISOR);
        assertThat(extraObjects).as("view overhead adds a few wrapper objects").isPositive();
        assertThat(extraObjects).isLessThanOrEqualTo(VIEW_OVERHEAD_OBJECT_LIMIT);
    }

    @Test
    @DisplayName("A2 真实模块访问：遍历 java.lang/java.util 并按稳定键行输出")
    void realModuleAccess_shouldTraverseJdkInternalsAndRenderStableLines() {
        assertThat(allScenariosRun.exitCode())
                .as("full scenario run exit code, output: %s", failureDetail(allScenariosRun))
                .isZero();

        final List<ScenarioReport> reports = parseBlocks(allScenariosRun.stdout());

        final List<String> classNames = reports.stream()
                .flatMap(report -> report.classes().stream())
                .map(ClassLine::className)
                .toList();
        assertThat(classNames).as("java.lang payloads are reachable").contains("java.lang.String");
        assertThat(classNames).as("java.util collection storage is reachable")
                .anyMatch(className -> className.startsWith("java.util."));

        reports.forEach(report -> assertThat(report.classes())
                .as("class aggregation of %s is ordered by descending bytes", report.scenario())
                .isSortedAccordingTo(Comparator.comparingLong(ClassLine::bytes).reversed()));
    }

    @Test
    @DisplayName("A2 缺省 --add-opens 应响亮失败而非静默跳过")
    void missingAddOpens_shouldFailLoudlyWithoutSilentSkip() {
        assertThat(withoutOpensRun.exitCode())
                .as("a run that cannot read a JDK field must exit with a failure code")
                .isEqualTo(1);
        assertThat(withoutOpensRun.stdout())
                .as("no partial report may be written when the traversal cannot complete")
                .isEmpty();
        assertThat(withoutOpensRun.stderr())
                .as("the diagnostic names the unreachable field and the required option")
                .contains("Cannot read")
                .contains("--add-opens")
                .doesNotContain("scenario=");
    }

    @Test
    @DisplayName("--scenario 选项应只测量请求的场景并按请求顺序输出")
    void scenarioOption_shouldMeasureOnlyTheRequestedScenariosInOrder() {
        assertThat(filteredRun.exitCode())
                .as("filtered run exit code, output: %s", failureDetail(filteredRun))
                .isZero();

        final List<ScenarioReport> reports = parseBlocks(filteredRun.stdout());

        assertThat(reports).extracting(ScenarioReport::scenario)
                .containsExactly("calculateOnly", "repeatedAcquire");
    }

    /**
     * 构建 shade 后的可执行基准 jar。
     */
    private static void buildShadedJar() {
        final ProcessResult packageResult = runCommand(
                List.of("mvn", "-B", "-o", "-DskipTests", "-pl", "change-tracking-benchmark",
                        "-am", "-Pbench", "package"),
                REPOSITORY_DIRECTORY, PACKAGE_TIMEOUT_SECONDS);
        assertThat(packageResult.exitCode())
                .as("shading build exit code, output: %s", failureDetail(packageResult))
                .isZero();
        assertThat(Files.exists(BENCHMARK_JAR)).as("shaded benchmark jar %s", BENCHMARK_JAR).isTrue();
    }

    /**
     * 以子进程运行入口一次。
     *
     * @param probeArguments 解析入口之前的参数（场景选项或空）
     * @param javaOptions    子进程 JVM 参数（模块开放或空）
     * @return 子进程的执行结果
     */
    private static ProcessResult runProbe(final List<String> probeArguments, final List<String> javaOptions) {
        final List<String> command = new ArrayList<>(List.of("java"));
        command.addAll(javaOptions);
        command.addAll(List.of("-cp", BENCHMARK_JAR.toString(), RetainedMemoryMain.class.getName()));
        command.addAll(probeArguments);
        return runCommand(command, MODULE_DIRECTORY, RUN_TIMEOUT_SECONDS);
    }

    /**
     * 在指定目录执行外部命令，标准输出与标准错误分别落盘后读回。
     *
     * @param command          命令与参数
     * @param workingDirectory 工作目录
     * @param timeoutSeconds   超时秒数
     * @return 退出码与两路输出
     */
    private static ProcessResult runCommand(final List<String> command, final Path workingDirectory,
                                            final long timeoutSeconds) {
        final Path stdoutFile = createTempFile("retained-memory-stdout-");
        final Path stderrFile = createTempFile("retained-memory-stderr-");
        final ProcessBuilder builder = new ProcessBuilder(new ArrayList<>(command));
        builder.directory(workingDirectory.toFile());
        builder.redirectOutput(stdoutFile.toFile());
        builder.redirectError(stderrFile.toFile());
        try {
            final Process process = builder.start();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new ProcessResult(-1, "command timed out after " + timeoutSeconds + "s: " + command, "");
            }
            return new ProcessResult(process.exitValue(), Files.readString(stdoutFile), Files.readString(stderrFile));
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to run " + command, e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running " + command, e);
        }
    }

    /**
     * 创建临时输出文件。
     *
     * @param prefix 文件名前缀
     * @return 临时文件路径
     */
    private static Path createTempFile(final String prefix) {
        try {
            final Path file = Files.createTempFile(prefix, ".log");
            file.toFile().deleteOnExit();
            return file;
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to create the command log file", e);
        }
    }

    /**
     * 把入口的标准输出按空行切分为场景块并严格解析每条键行。
     *
     * @param stdout 入口的标准输出
     * @return 每个场景一块的解析结果
     */
    private static List<ScenarioReport> parseBlocks(final String stdout) {
        final List<ScenarioReport> reports = new ArrayList<>();
        final List<String> block = new ArrayList<>();
        for (final String line : stdout.split("\\R", -1)) {
            if (line.isBlank()) {
                if (!block.isEmpty()) {
                    reports.add(parseBlock(block));
                    block.clear();
                }
                continue;
            }
            block.add(line);
        }
        if (!block.isEmpty()) {
            reports.add(parseBlock(block));
        }
        return reports;
    }

    /**
     * 严格解析一个场景块：五个固定键头部行按固定顺序，其后每条类行符合稳定格式。
     *
     * @param lines 场景块的非空行
     * @return 该场景的结构化报告
     */
    private static ScenarioReport parseBlock(final List<String> lines) {
        if (lines.size() < 5) {
            throw new IllegalStateException("Report block shorter than its five header lines: " + lines);
        }
        final String scenario = valueOf(lines.get(0), "scenario=");
        final String shape = valueOf(lines.get(1), "shape=");
        final List<String> heldResults = List.of(valueOf(lines.get(2), "heldResults=").split(",", -1));
        final long retainedBytes = Long.parseLong(valueOf(lines.get(3), "retainedBytes="));
        final long retainedObjects = Long.parseLong(valueOf(lines.get(4), "retainedObjects="));
        final List<ClassLine> classes = new ArrayList<>();
        for (int index = 5; index < lines.size(); index++) {
            final Matcher matcher = CLASS_LINE.matcher(lines.get(index));
            if (!matcher.matches()) {
                throw new IllegalStateException("Malformed class line: " + lines.get(index));
            }
            classes.add(new ClassLine(matcher.group(1), Long.parseLong(matcher.group(2)),
                    Long.parseLong(matcher.group(3))));
        }
        return new ScenarioReport(scenario, shape, heldResults, retainedBytes, retainedObjects, classes);
    }

    /**
     * 取出带指定固定键的行的值。
     *
     * @param line 待解析行
     * @param key  固定键（含等号）
     * @return 去键后的值
     */
    private static String valueOf(final String line, final String key) {
        if (!line.startsWith(key)) {
            throw new IllegalStateException("Expected a line starting with " + key + " but was: " + line);
        }
        return line.substring(key.length());
    }

    /**
     * 取指定场景的解析结果。
     *
     * @param reports  全部场景块
     * @param scenario 目标场景令牌
     * @return 该场景的解析结果
     */
    private static ScenarioReport reportFor(final List<ScenarioReport> reports, final String scenario) {
        return reports.stream()
                .filter(report -> report.scenario().equals(scenario))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No report for scenario " + scenario));
    }

    /**
     * 汇总子进程的两路输出，用于失败断言消息。
     *
     * @param result 子进程执行结果
     * @return 标准输出与标准错误的尾部
     */
    private static String failureDetail(final ProcessResult result) {
        return tail(result.stdout()) + tail(result.stderr());
    }

    /**
     * 截取输出尾部。
     *
     * @param output 输出文本
     * @return 最后 2000 个字符
     */
    private static String tail(final String output) {
        final int start = Math.max(0, output.length() - 2_000);
        return output.substring(start);
    }

    /**
     * 一个场景块的解析结果。
     *
     * @param scenario       场景令牌
     * @param shape          冻结负载名
     * @param heldResults    持有结果令牌
     * @param retainedBytes  保留字节数
     * @param retainedObjects 保留对象数
     * @param classes        按类聚合的类行
     */
    private record ScenarioReport(String scenario, String shape, List<String> heldResults, long retainedBytes,
                                  long retainedObjects, List<ClassLine> classes) {
    }

    /**
     * 一条类聚合行的解析结果。
     *
     * @param className 类名（二进制名）
     * @param count     该类可达对象数
     * @param bytes     该类可达对象浅尺寸总和
     */
    private record ClassLine(String className, long count, long bytes) {
    }

    /**
     * 一次子进程的执行结果。
     *
     * @param exitCode 退出码，超时为 -1
     * @param stdout   标准输出
     * @param stderr   标准错误
     */
    private record ProcessResult(int exitCode, String stdout, String stderr) {
    }
}
