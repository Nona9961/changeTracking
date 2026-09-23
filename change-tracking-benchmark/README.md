# change-tracking-benchmark

性能基准模块：以 JMH 测量 changeTracking 关键路径的时间开销与每次操作的分配字节数，
并在运行期采集环境信息，随结果一并归档。模块不发布、不进入库的依赖面。

## 快速使用

执行模块内的一条命令载体：

```bash
change-tracking-benchmark/bench.sh
```

`bench.sh` 先以 `mvn -q -DskipTests -pl change-tracking-benchmark -am -Pbench package`
由 `maven-shade-plugin` 打包出自包含的 `target/benchmarks.jar`（`Main-Class` 为
`org.openjdk.jmh.Main`，保留 `META-INF/services`），再在模块目录执行：

```bash
java -jar target/benchmarks.jar -rf json -rff target/benchmark-results/jmh-result.json -prof gc
```

基准默认不随 `mvn test` 执行，由人显式触发。

## 运行产物

| 产物 | 路径（相对本模块） | 说明 |
|------|------------------|------|
| 基准结果 | `target/benchmark-results/jmh-result.json` | JMH 原生 JSON（`-rf json`），含时间指标与 `-prof gc` 的分配字节数（B/op） |
| 环境记录 | `target/benchmark-results/environment.json` | 运行期采集的 JDK 版本、JVM 参数、GC 收集器与机器标识 |

## 结果归档与对比

运行产物按运行标识归档，并可对两次运行产出差异表。两条入口都在 shaded jar 内，用 `-cp` 调用（jar 的 `Main-Class` 是 JMH）：

```bash
# 归档最近一次运行；--id 可选，省略时目录名只有 UTC 时间戳
java -cp target/benchmarks.jar com.nona.changeTracking.bench.result.ArchiveResultsMain --id <运行标识>

# 对比两次运行的结果 JSON
java -cp target/benchmarks.jar com.nona.changeTracking.bench.result.CompareResultsMain \
    --first benchmark/results/<运行标识>-<UTC 时间戳>/jmh-result.json \
    --second benchmark/results/<运行标识>-<UTC 时间戳>/jmh-result.json
```

归档入口的 `--source` 缺省为 `target/benchmark-results`、`--root` 缺省为 `benchmark/results`（均相对本模块）；目录名固定为 `<运行标识>-<UTC 时间戳>`，同名时自动追加 `-2`、`-3`…，既有归档不被覆盖；运行标识由调用方给出，工具不调用 git 推断。

对比入口按基准名与参数配对条目，输出差异表：每条给出两侧数值、差值与显著性判定，差值落在两份报告误差内的条目标为 `insignificant`（不显著）；两次结果的同一指标单位不一致时拒绝整次比较。

两条入口都把结果（差异表、归档目录路径）写 **stdout**、被拒诊断写 **stderr**，退出码 0 表示成功、1 表示请求被拒或读取失败；输出不依赖运行期日志后端。

归档目录整体不进版本管理（仓库忽略 `benchmark/results/*`）；要留存的运行由人挑选后放入 `benchmark/results/committed/` 再提交。

## 负载样本族

`bench/sample` 提供冻结的样本形态：`SampleShape` 描述字段数、嵌套深度与集合规模，
`SampleFamily` 是唯一构造入口，`SampleMutator` 提供属性变更与集合值替换、增删、重排操作。
四条基准 task 复用该样本族，不自建样本。

## 环境记录

`bench/env` 通过 `EnvironmentProbe` 端口读取运行事实，`EnvironmentRecordCollector` 校验后组装
`EnvironmentRecord`，`EnvironmentRecordWriter` 在基准 `setUp` 中把它写入结果目录，与 JMH 结果同目录归档。