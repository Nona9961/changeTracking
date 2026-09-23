package com.nona.changeTracking.bench;

import java.util.List;

/**
 * 快照扫描维度（嵌套深度与集合规模）的测试侧真值源：供该路径的单元测试与装配面集成测试引用。
 * <p>
 * 基准类的 {@code @Param} 保持字面量，本类是与其独立的真值副本，因此「注解声明值等于本类真值」的
 * 断言仍能捕获档位变更。字段数维不由本类承载：其真值由
 * {@link com.nona.changeTracking.bench.sample.SampleShape#SUPPORTED_FIELD_COUNT_LOW} 与
 * {@link com.nona.changeTracking.bench.sample.SampleShape#SUPPORTED_FIELD_COUNT_HIGH} 提供。
 * 端到端路径的单测 {@link EndToEndBenchmarkUnitTest} 持有同一组深度与规模档位的独立真值副本。
 */
final class FrozenScanLevels {

    /** 冻结的嵌套深度档位，声明顺序与 {@code @Param} 的声明顺序一致。 */
    static final List<Integer> NESTING_DEPTH = List.of(1, 2, 3, 4, 5);

    /** 冻结的集合规模小档。 */
    static final int COLLECTION_SIZE_SMALL = 10;

    /** 冻结的集合规模中档。 */
    static final int COLLECTION_SIZE_MEDIUM = 100;

    /** 冻结的集合规模大档。 */
    static final int COLLECTION_SIZE_LARGE = 1_000;

    /** 冻结的集合规模档位，声明顺序与 {@code @Param} 的声明顺序一致。 */
    static final List<Integer> COLLECTION_SIZE =
            List.of(COLLECTION_SIZE_SMALL, COLLECTION_SIZE_MEDIUM, COLLECTION_SIZE_LARGE);

    /**
     * 私有构造器：本类是常量持有者，不实例化。
     */
    private FrozenScanLevels() {
    }
}
