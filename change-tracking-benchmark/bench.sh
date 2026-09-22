#!/usr/bin/env bash
#
# 基准的一条命令载体：先 shade 出可执行基准 jar，再以 JMH 运行。
# 用法：在任意目录执行 <repo>/change-tracking-benchmark/bench.sh
#
set -euo pipefail

MODULE_DIR="$(cd "$(dirname "$0")" && pwd)"
REPOSITORY_DIR="$(dirname "$MODULE_DIR")"

cd "$REPOSITORY_DIR"
mvn -q -DskipTests -pl change-tracking-benchmark -am -Pbench package

cd "$MODULE_DIR"
mkdir -p target/benchmark-results
java -jar target/benchmarks.jar -rf json -rff target/benchmark-results/jmh-result.json -prof gc