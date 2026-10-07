package com.nona.changeTracking.bench;

import java.nio.file.Path;
import java.util.List;

/**
 * 共享子图复用基准载体的装配面共享 fixture：复用既有 {@link BenchmarkRunFixture} 的 shade
 * 构建与外部命令执行设施，只补上本载体的锚定全限定名选择器、结果目录与运行命令。
 * <p>
 * {@link SharedSubgraphBenchmark} 在一条 JMH 命令中真实运行：shade 后按类级冻结注解协议
 * （{@code AverageTime}、{@code us/op}、3×1s 预热、5×1s 测量、{@code @Fork(1)}）执行四个拓扑各自的
 * 0/1 变更档位，{@code -prof gc} 附带 {@code gc.alloc.rate.norm}。
 * <p>
 * 选择器锚定到类的全限定名后缀 {@code \.SharedSubgraphBenchmark\.} 而非短类名：JMH 按正则搜索全限定
 * 基准名，短模式会命中其它类的子串。
 * <p>
 * 第二次运行与第一次同协议，仅供 {@code CompareResultsMain} 按内容派生的条目 key 做配对校验。
 * <p>
 * 该类不声明测试，也不是 {@code *Test} 命名，surefire 不收集；shade 构建经
 * {@link BenchmarkRunFixture#shadedJar()} 在模块内共享一次。
 */
final class SharedSubgraphBenchmarkRunFixture {

    /** 模块目录，surefire 测试 JVM 的工作目录。 */
    static final Path MODULE_DIRECTORY = BenchmarkRunFixture.MODULE_DIRECTORY;

    /** 第一次运行的隔离结果目录。 */
    static final Path RESULT_DIRECTORY = MODULE_DIRECTORY.resolve("target").resolve("shared-subgraph-results");

    /** 第一次运行的 JMH 原生结果文件。 */
    static final Path RESULT_FILE = RESULT_DIRECTORY.resolve("jmh-result.json");

    /** 配对运行的隔离结果目录。 */
    static final Path PAIRING_RESULT_DIRECTORY =
            MODULE_DIRECTORY.resolve("target").resolve("shared-subgraph-pairing-results");

    /** 配对运行的 JMH 原生结果文件。 */
    static final Path PAIRING_RESULT_FILE = PAIRING_RESULT_DIRECTORY.resolve("jmh-result.json");

    /**
     * 锚定全限定名前缀的选择器：只命中本基准载体类，不命中任何子串相同的其它基准。
     */
    private static final String BENCHMARK_SELECTOR =
            "com\\.nona\\.changeTracking\\.bench\\.SharedSubgraphBenchmark\\.";

    /** 保护惰性运行结果的锁。 */
    private static final Object LOCK = new Object();

    /** 第一次运行的命令结果，首次请求前为 null。 */
    private static BenchmarkRunFixture.ProcessResult runResult;

    /** 配对运行的命令结果，首次请求前为 null。 */
    private static BenchmarkRunFixture.ProcessResult pairingRunResult;

    /**
     * 私有构造器：本类是静态 fixture。
     */
    private SharedSubgraphBenchmarkRunFixture() {
    }

    /**
     * 返回第一次真实运行的结果，执行 shade 构建与该命令各一次。
     *
     * @return 四个拓扑在一条命令中按冻结协议运行的结果
     */
    static BenchmarkRunFixture.ProcessResult run() {
        synchronized (LOCK) {
            BenchmarkRunFixture.shadedJar();
            if (runResult == null) {
                BenchmarkRunFixture.createDirectory(RESULT_DIRECTORY);
                runResult = BenchmarkRunFixture.run(
                        BenchmarkRunFixture.command(BENCHMARK_SELECTOR, List.of(), RESULT_FILE),
                        MODULE_DIRECTORY, BenchmarkRunFixture.RUN_TIMEOUT_SECONDS);
            }
            return runResult;
        }
    }

    /**
     * 返回配对运行的结果，执行同协议的命令一次。
     *
     * @return 第二次同协议运行的结果
     */
    static BenchmarkRunFixture.ProcessResult pairingRun() {
        synchronized (LOCK) {
            BenchmarkRunFixture.shadedJar();
            if (pairingRunResult == null) {
                BenchmarkRunFixture.createDirectory(PAIRING_RESULT_DIRECTORY);
                pairingRunResult = BenchmarkRunFixture.run(
                        BenchmarkRunFixture.command(BENCHMARK_SELECTOR, List.of(), PAIRING_RESULT_FILE),
                        MODULE_DIRECTORY, BenchmarkRunFixture.RUN_TIMEOUT_SECONDS);
            }
            return pairingRunResult;
        }
    }
}
