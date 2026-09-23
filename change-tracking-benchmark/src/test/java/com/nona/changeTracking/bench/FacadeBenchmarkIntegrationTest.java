package com.nona.changeTracking.bench;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FacadeBenchmark} 的装配面集成测试：成对基准经 shade 后的 {@code benchmarks.jar} 进入真实
 * JMH 运行并产出 4 条可用条目，直连侧经 shade jar 的真实 SPI 发现其提供者，两条装配路径的字节码
 * 引用面保持成对设计所要求的两条不同路径。
 * <p>
 * The JMH run of this test shortens the frozen warmup and measurement windows
 * ({@code -wi 2 -i 3 -w 200ms -r 200ms}) to keep the integration test short. The shortening weakens
 * none of the criteria checked here: the four entries are declared by the four {@code @Benchmark}
 * methods, which a shortened run expands unchanged; the emptiness of the parameter map does not
 * depend on the window length; and the positivity of the time and allocation metrics follows from the
 * measured operation itself, because both a complete assembly and a {@code track} call allocate.
 * <p>
 * The reference surface check reads the constant pool of the shaded class files, which no unit test
 * can observe: the direct path must reach the provider through {@code java.util.ServiceLoader} and
 * must not mention the facade or the {@code internal} package, while the facade path must mention the
 * facade type and must not mention the service loader. Both halves together pin the pairing to two
 * genuinely different assembly paths.
 * <p>
 * Like {@code BenchmarkModuleIntegrationTest}, this class is excluded by the default surefire
 * configuration ({@code **}{@code /}{@code *IntegrationTest}) and runs under {@code -Pfull} only. A
 * failing JMH run still exits with code zero and writes zero entries, so every assertion here works on
 * the strictly parsed result json instead of the exit code alone.
 */
@DisplayName("FacadeBenchmark 装配面集成测试")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FacadeBenchmarkIntegrationTest {

    /** 本模块目录，surefire 的工作目录。 */
    private static final Path MODULE_DIRECTORY = Path.of("").toAbsolutePath();

    /** 仓库根目录，shade 构建在此执行。 */
    private static final Path REPOSITORY_DIRECTORY = MODULE_DIRECTORY.getParent();

    /** shade 产出的可执行基准 jar。 */
    private static final Path BENCHMARK_JAR = MODULE_DIRECTORY.resolve("target").resolve("benchmarks.jar");

    /** 本次集成测试的运行结果目录，与模块 smoke 测试及快照扫描测试的结果目录隔离。 */
    private static final Path RESULT_DIRECTORY = MODULE_DIRECTORY.resolve("target").resolve("facade-pair-results");

    /** 本次运行的 JMH 原生 JSON 结果文件。 */
    private static final Path JMH_RESULT_FILE = RESULT_DIRECTORY.resolve("jmh-result.json");

    /** 只选择本 task 的基准类，不混入其他基准类的条目。 */
    private static final String BENCHMARK_SELECTOR = "FacadeBenchmark";

    /** shade 后 core 的 SPI 服务文件路径。 */
    private static final String SPI_SERVICE_ENTRY =
            "META-INF/services/com.nona.changeTracking.spi.TrackingCapabilityProvider";

    /** shade 后应可见的 core 追踪能力提供者。 */
    private static final String CORE_PROVIDER =
            "com.nona.changeTracking.internal.capability.DefaultTrackingCapabilityProvider";

    /** 直连装配路径类的字节码条目。 */
    private static final String DIRECT_PATH_CLASS_ENTRY =
            "com/nona/changeTracking/bench/FacadeBenchmark$DirectPath.class";

    /** 门面装配路径类的字节码条目。 */
    private static final String FACADE_PATH_CLASS_ENTRY =
            "com/nona/changeTracking/bench/FacadeBenchmark$FacadePath.class";

    /** 直连侧应引用的 SPI 发现类型。 */
    private static final String SERVICE_LOADER_TYPE = "java/util/ServiceLoader";

    /** 门面侧应引用的门面类型。 */
    private static final String FACADE_TYPE = "com/nona/changeTracking/api/ChangeTrackerFactory";

    /** 直连侧禁止引用的实现包前缀。 */
    private static final String INTERNAL_PACKAGE = "com/nona/changeTracking/internal";

    /** 冻结的条目总数：2 对 × 2 侧。 */
    private static final int EXPECTED_ENTRY_COUNT = 4;

    /** 成对的两个动作词，条目按此分为两对。 */
    private static final List<String> PAIRED_ACTIONS = List.of("Build", "Track");

    /** 测量的平均时间单位，由 {@code @OutputTimeUnit(MICROSECONDS)} 决定。 */
    private static final String TIME_UNIT = "us/op";

    /** 分配指标的字节计量单位。 */
    private static final String ALLOCATION_UNIT = "B/op";

    /** 冻结的四条条目全限定名：门面/直连 × 装配/操作。 */
    private static final Set<String> EXPECTED_BENCHMARKS = Set.of(
            FacadeBenchmark.class.getName() + ".facadeBuild",
            FacadeBenchmark.class.getName() + ".directBuild",
            FacadeBenchmark.class.getName() + ".facadeTrack",
            FacadeBenchmark.class.getName() + ".directTrack");

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

    /** class 文件魔数。 */
    private static final int CLASS_FILE_MAGIC = 0xCAFEBABE;

    /** 魔数之后的次版本与主版本字节数。 */
    private static final int CLASS_FILE_VERSION_BYTES = 4;

    /** 常量池条目下标从 1 开始，0 为保留位。 */
    private static final int FIRST_CONSTANT_POOL_INDEX = 1;

    /** 双字节常量池条目的字节数。 */
    private static final int TWO_BYTE_ENTRY_BYTES = 2;

    /** 三字节常量池条目的字节数。 */
    private static final int THREE_BYTE_ENTRY_BYTES = 3;

    /** 四字节常量池条目的字节数。 */
    private static final int FOUR_BYTE_ENTRY_BYTES = 4;

    /** 八字节常量池条目的字节数。 */
    private static final int EIGHT_BYTE_ENTRY_BYTES = 8;

    /** 常量池 tag：UTF-8 字符串。 */
    private static final int CONSTANT_UTF8 = 1;

    /** 常量池 tag：int 字面量。 */
    private static final int CONSTANT_INTEGER = 3;

    /** 常量池 tag：float 字面量。 */
    private static final int CONSTANT_FLOAT = 4;

    /** 常量池 tag：long 字面量，占用两个条目槽。 */
    private static final int CONSTANT_LONG = 5;

    /** 常量池 tag：double 字面量，占用两个条目槽。 */
    private static final int CONSTANT_DOUBLE = 6;

    /** 常量池 tag：类或接口。 */
    private static final int CONSTANT_CLASS = 7;

    /** 常量池 tag：字符串字面量。 */
    private static final int CONSTANT_STRING = 8;

    /** 常量池 tag：字段引用。 */
    private static final int CONSTANT_FIELD_REF = 9;

    /** 常量池 tag：方法引用。 */
    private static final int CONSTANT_METHOD_REF = 10;

    /** 常量池 tag：接口方法引用。 */
    private static final int CONSTANT_INTERFACE_METHOD_REF = 11;

    /** 常量池 tag：名称与类型描述符。 */
    private static final int CONSTANT_NAME_AND_TYPE = 12;

    /** 常量池 tag：方法句柄。 */
    private static final int CONSTANT_METHOD_HANDLE = 15;

    /** 常量池 tag：方法类型描述符。 */
    private static final int CONSTANT_METHOD_TYPE = 16;

    /** 常量池 tag：动态常量。 */
    private static final int CONSTANT_DYNAMIC = 17;

    /** 常量池 tag：动态调用点。 */
    private static final int CONSTANT_INVOKE_DYNAMIC = 18;

    /** 常量池 tag：模块。 */
    private static final int CONSTANT_MODULE = 19;

    /** 常量池 tag：包。 */
    private static final int CONSTANT_PACKAGE = 20;

    /** 严格 JSON 解析脚本：条目数与指标齐备在此断言，输出行由 Java 侧断言。 */
    private static final String JMH_PARSE_SCRIPT = """
            import json, sys
            with open(sys.argv[1], encoding="utf-8") as handle:
                data = json.load(handle)
            assert isinstance(data, list), "result json is not a list: %r" % type(data)
            assert len(data) == 4, "expected 4 benchmark entries, got %d" % len(data)
            for result in data:
                params = result.get("params") or {}
                assert len(params) == 0, "expected no parameter in %s, got %r" % (
                    result.get("benchmark", ""), params)
                primary = result.get("primaryMetric") or {}
                alloc = (result.get("secondaryMetrics") or {}).get("gc.alloc.rate.norm") or {}
                assert primary.get("score") is not None, "missing time score in %s" % result.get("benchmark", "")
                assert alloc.get("score") is not None, "missing allocation score in %s" % result.get("benchmark", "")
                print("ENTRY|%s|%s|%s|%s|%s" % (result.get("benchmark", ""), primary.get("score"),
                    primary.get("scoreUnit", ""), alloc.get("score"), alloc.get("scoreUnit", "")))
            """;

    /** 严格 JSON 解析输出行的前缀。 */
    private static final String ENTRY_PREFIX = "ENTRY|";

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
    @DisplayName("shade 后经真实 JMH 运行应产出 4 条成对条目，无参数且时间与分配均为正")
    void shadedJmhRun_shouldProduceFourCompleteEntriesWithPositiveMetrics() {
        assertThat(packageResult.exitCode())
                .as("shade build exit code, output tail: %s", tail(packageResult.output()))
                .isZero();
        assertThat(benchmarkRunResult.exitCode())
                .as("jmh run exit code, output tail: %s", tail(benchmarkRunResult.output()))
                .isZero();
        assertThat(Files.exists(BENCHMARK_JAR)).as("shaded benchmark jar").isTrue();
        assertThat(Files.exists(JMH_RESULT_FILE)).as("jmh result json").isTrue();

        final List<Entry> entries = parsedEntries();

        assertThat(entries).as("result entries of the paired benchmark").hasSize(EXPECTED_ENTRY_COUNT);
        assertThat(entries.stream().map(Entry::benchmark).collect(Collectors.toSet()))
                .as("measured methods of the paired benchmark")
                .containsExactlyInAnyOrderElementsOf(EXPECTED_BENCHMARKS);
        assertThat(entries).allSatisfy(entry -> {
            assertThat(entry.timeUnit()).as("time unit of %s", entry.benchmark()).isEqualTo(TIME_UNIT);
            assertThat(entry.time()).as("time per operation of %s", entry.benchmark()).isPositive();
            assertThat(entry.allocUnit()).as("allocation unit of %s", entry.benchmark()).isEqualTo(ALLOCATION_UNIT);
            assertThat(entry.allocRate()).as("allocation per operation of %s", entry.benchmark()).isPositive();
        });
    }

    @Test
    @Order(2)
    @DisplayName("shade 后 SPI 仍可发现，且两条装配路径的引用面保持分离")
    void shadedJar_shouldKeepTheSpiDiscoverableAndTheReferenceSurfacesSeparated() throws IOException {
        try (JarFile jar = new JarFile(BENCHMARK_JAR.toFile())) {
            assertShadedSpiIsDiscoverable(jar);
            assertReferenceSurfaces(jar);
        }
    }

    @Test
    @Order(3)
    @DisplayName("成对可比：同对两侧条目齐备、单位一致，装配对两侧分配均为正")
    void pairedEntries_shouldBeComparable() {
        final Map<String, Entry> entriesByAction = entriesByAction(parsedEntries());

        assertThat(entriesByAction.keySet())
                .as("actions of the paired benchmark")
                .containsExactlyInAnyOrder("facadeBuild", "directBuild", "facadeTrack", "directTrack");
        for (final String action : PAIRED_ACTIONS) {
            final Entry facade = entriesByAction.get("facade" + action);
            final Entry direct = entriesByAction.get("direct" + action);

            assertThat(facade.timeUnit()).as("time unit of the %s pair", action).isEqualTo(direct.timeUnit());
            assertThat(facade.allocUnit()).as("allocation unit of the %s pair", action).isEqualTo(direct.allocUnit());
        }
        assertThat(entriesByAction.get("facadeBuild").allocRate()).as("facade build allocation").isPositive();
        assertThat(entriesByAction.get("directBuild").allocRate()).as("direct build allocation").isPositive();
    }

    /**
     * 校验 shade 后的 jar 仍暴露 core 的 SPI 提供者。
     *
     * @param jar shade 后的基准 jar
     * @throws IOException 读取 jar 失败
     */
    private static void assertShadedSpiIsDiscoverable(final JarFile jar) throws IOException {
        final JarEntry serviceEntry = jar.getJarEntry(SPI_SERVICE_ENTRY);

        assertThat(serviceEntry).as("shaded spi service entry %s", SPI_SERVICE_ENTRY).isNotNull();
        assertThat(new String(jar.getInputStream(serviceEntry).readAllBytes(), StandardCharsets.UTF_8))
                .as("spi providers of the shaded jar")
                .contains(CORE_PROVIDER);
    }

    /**
     * 校验两条装配路径的字节码引用面：直连侧只经 SPI 扩展点、不引用门面与 internal 包，门面侧只引用门面类型。
     *
     * @param jar shade 后的基准 jar
     * @throws IOException 读取 jar 失败
     */
    private static void assertReferenceSurfaces(final JarFile jar) throws IOException {
        final Set<String> directEntries = constantPoolEntries(jar, DIRECT_PATH_CLASS_ENTRY);
        final Set<String> facadeEntries = constantPoolEntries(jar, FACADE_PATH_CLASS_ENTRY);

        assertThat(directEntries).as("reference surface of the direct path")
                .anyMatch(entry -> entry.contains(SERVICE_LOADER_TYPE))
                .noneMatch(entry -> entry.contains("ChangeTrackerFactory"))
                .noneMatch(entry -> entry.contains(INTERNAL_PACKAGE));
        assertThat(facadeEntries).as("reference surface of the facade path")
                .anyMatch(entry -> entry.contains(FACADE_TYPE))
                .noneMatch(entry -> entry.contains("ServiceLoader"));
    }

    /**
     * 读取 shade 后某个 class 条目的常量池 Utf8 条目集合。
     * <p>
     * 常量池布局：魔数、次版本与主版本各 2 字节、条目数 2 字节，随后是编号 1 起的条目；UTF-8 条目携带
     * 其长度前缀，类/字符串/方法类型/模块/包引用各 2 字节，方法句柄 3 字节，数值字面量与各种引用
     * 4 字节，long 与 double 各 8 字节并占用两个条目槽。
     *
     * @param jar        shade 后的基准 jar
     * @param classEntry class 条目路径
     * @return 常量池中的 Utf8 字符串集合
     * @throws IOException 读取 jar 失败
     */
    private static Set<String> constantPoolEntries(final JarFile jar, final String classEntry) throws IOException {
        final JarEntry entry = jar.getJarEntry(classEntry);

        assertThat(entry).as("shaded class entry %s", classEntry).isNotNull();
        try (DataInputStream stream = new DataInputStream(jar.getInputStream(entry))) {
            assertThat(stream.readInt()).as("class file magic of %s", classEntry).isEqualTo(CLASS_FILE_MAGIC);
            stream.skipNBytes(CLASS_FILE_VERSION_BYTES);
            final int constantPoolCount = stream.readUnsignedShort();
            final Set<String> entries = new HashSet<>();
            for (int index = FIRST_CONSTANT_POOL_INDEX; index < constantPoolCount; index++) {
                final int tag = stream.readUnsignedByte();
                switch (tag) {
                    case CONSTANT_UTF8 -> entries.add(stream.readUTF());
                    case CONSTANT_CLASS, CONSTANT_STRING, CONSTANT_METHOD_TYPE, CONSTANT_MODULE, CONSTANT_PACKAGE ->
                            stream.skipNBytes(TWO_BYTE_ENTRY_BYTES);
                    case CONSTANT_METHOD_HANDLE -> stream.skipNBytes(THREE_BYTE_ENTRY_BYTES);
                    case CONSTANT_INTEGER, CONSTANT_FLOAT, CONSTANT_FIELD_REF, CONSTANT_METHOD_REF,
                         CONSTANT_INTERFACE_METHOD_REF, CONSTANT_NAME_AND_TYPE, CONSTANT_DYNAMIC,
                         CONSTANT_INVOKE_DYNAMIC -> stream.skipNBytes(FOUR_BYTE_ENTRY_BYTES);
                    case CONSTANT_LONG, CONSTANT_DOUBLE -> {
                        stream.skipNBytes(EIGHT_BYTE_ENTRY_BYTES);
                        index++;
                    }
                    default -> throw new IllegalStateException("Unknown constant pool tag " + tag
                            + " in " + classEntry);
                }
            }
            return entries;
        }
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
     * 把结果条目按测量方法名索引。
     *
     * @param entries 全部结果条目
     * @return 方法名 → 结果条目
     */
    private static Map<String, Entry> entriesByAction(final List<Entry> entries) {
        final Map<String, Entry> byAction = new LinkedHashMap<>();
        for (final Entry entry : entries) {
            final int separator = entry.benchmark().lastIndexOf('.');
            byAction.put(entry.benchmark().substring(separator + 1), entry);
        }
        return byAction;
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
            logFile = Files.createTempFile("facade-pair-integration-", ".log");
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
     * @param time      每次操作的平均时间
     * @param timeUnit  时间指标单位
     * @param allocRate 每次操作的分配字节数
     * @param allocUnit 分配指标单位
     */
    private record Entry(String benchmark, double time, String timeUnit, double allocRate, String allocUnit) {

        /**
         * 解析严格 JSON 脚本输出的条目行。
         *
         * @param value 去前缀后的条目行
         * @return 解析出的结果条目
         * @throws IllegalStateException 行结构不符合脚本约定
         */
        static Entry parse(final String value) {
            final String[] fields = value.split("\\|", -1);
            if (fields.length != 5) {
                throw new IllegalStateException("Malformed entry line: " + value);
            }
            return new Entry(fields[0], Double.parseDouble(fields[1]), fields[2], Double.parseDouble(fields[3]),
                    fields[4]);
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
