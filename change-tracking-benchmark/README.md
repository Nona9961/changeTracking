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

## 负载样本族

`bench/sample` 提供冻结的样本形态：`SampleShape` 描述字段数、嵌套深度与集合规模，
`SampleFamily` 是唯一构造入口，`SampleMutator` 提供属性变更与集合值替换、增删、重排操作。
四条基准 task 复用该样本族，不自建样本。

## 环境记录

`bench/env` 通过 `EnvironmentProbe` 端口读取运行事实，`EnvironmentRecordCollector` 校验后组装
`EnvironmentRecord`，`EnvironmentRecordWriter` 在基准 `setUp` 中把它写入结果目录，与 JMH 结果同目录归档。