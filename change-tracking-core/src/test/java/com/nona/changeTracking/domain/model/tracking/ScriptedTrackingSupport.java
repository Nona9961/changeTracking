package com.nona.changeTracking.domain.model.tracking;

import com.nona.changeTracking.domain.capability.ComparisonStrategy;
import com.nona.changeTracking.domain.capability.TrackingCapability;
import com.nona.changeTracking.domain.model.changeset.Change;
import com.nona.changeTracking.domain.model.snapshot.Snapshot;
import com.nona.changeTracking.domain.model.snapshot.ValueNodeSnapshot;
import com.nona.changeTracking.spi.SnapshotStrategy;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 非默认能力的测试支撑：用真实（非 Mockito 替身）的快照策略与比较策略实现装配追踪能力。
 * <p>
 * 用于验证扩展策略经既有 capability 与 tracker 入口工作的可用性：脚本只决定策略返回什么结果，
 * 快照类型、能力单元与调用链都是真实实现。
 */
final class ScriptedTrackingSupport {

    /**
     * 工具支撑类不实例化。
     */
    private ScriptedTrackingSupport() {
    }

    /**
     * 非默认快照类型：以字符串标记承载被追踪对象。
     *
     * @param marker 标记文本
     */
    record PlainSnapshot(String marker) implements Snapshot<String> {

        /**
         * {@inheritDoc}
         */
        @Override
        public String getSnapshotData() {
            return this.marker;
        }
    }

    /**
     * 非默认快照策略：把被追踪对象渲染为标记文本。
     */
    static final class PlainSnapshotStrategy implements SnapshotStrategy<PlainSnapshot> {

        /**
         * {@inheritDoc}
         */
        @Override
        public PlainSnapshot createSnapshot(final Object entity) {
            return new PlainSnapshot(String.valueOf(entity));
        }
    }

    /**
     * 脚本化比较策略：返回预设结果列表（可为 null）或在调用时抛出预设异常。
     */
    static final class ScriptedComparisonStrategy implements ComparisonStrategy<PlainSnapshot> {

        /**
         * 预设结果提供者；返回 null 表示策略违反结果契约。
         */
        private final Supplier<List<Change>> results;

        /**
         * 预设失败；非 null 时在比较时抛出。
         */
        private final RuntimeException failure;

        /**
         * 创建脚本化比较策略。
         *
         * @param results 结果提供者
         * @param failure 预设失败
         */
        private ScriptedComparisonStrategy(final Supplier<List<Change>> results, final RuntimeException failure) {
            this.results = results;
            this.failure = failure;
        }

        /**
         * 返回固定结果的脚本化策略。
         *
         * @param results 预设结果
         * @return 脚本化比较策略
         */
        static ScriptedComparisonStrategy returning(final List<Change> results) {
            return new ScriptedComparisonStrategy(() -> results, null);
        }

        /**
         * 返回延迟构造结果的脚本化策略（结果在比较时构造，用于让非法结果在比较入口被拒绝）。
         *
         * @param supplier 结果提供者
         * @return 脚本化比较策略
         */
        static ScriptedComparisonStrategy supplying(final Supplier<List<Change>> supplier) {
            return new ScriptedComparisonStrategy(supplier, null);
        }

        /**
         * 返回违反结果契约（返回 null）的脚本化策略。
         *
         * @return 脚本化比较策略
         */
        static ScriptedComparisonStrategy returningNull() {
            return new ScriptedComparisonStrategy(() -> null, null);
        }

        /**
         * 返回比较时抛出预设异常的脚本化策略。
         *
         * @param failure 预设失败
         * @return 脚本化比较策略
         */
        static ScriptedComparisonStrategy failing(final RuntimeException failure) {
            return new ScriptedComparisonStrategy(() -> List.of(), Objects.requireNonNull(failure, "failure"));
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public Class<PlainSnapshot> getSupportedSnapshotType() {
            return PlainSnapshot.class;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public List<Change> compare(final PlainSnapshot oldSnapshot, final PlainSnapshot newSnapshot) {
            if (this.failure != null) {
                throw this.failure;
            }
            return this.results.get();
        }
    }

    /**
     * 声明支持类型与实际快照类型不符的比较策略：用于验证类型守卫在比较前拒绝。
     */
    static final class ForeignTypeComparisonStrategy implements ComparisonStrategy<PlainSnapshot> {

        /**
         * {@inheritDoc}
         * <p>
         * 有意声明另一种快照类型，模拟误用能力单元的组合。
         */
        @SuppressWarnings("unchecked")
        @Override
        public Class<PlainSnapshot> getSupportedSnapshotType() {
            return (Class<PlainSnapshot>) (Class<?>) ValueNodeSnapshot.class;
        }

        /**
         * {@inheritDoc}
         * <p>
         * 类型守卫应在比较前拒绝，本方法不可达。
         */
        @Override
        public List<Change> compare(final PlainSnapshot oldSnapshot, final PlainSnapshot newSnapshot) {
            throw new AssertionError("类型守卫应在比较前拒绝不匹配的快照");
        }
    }

    /**
     * 脚本化追踪能力单元：真实组合一个快照策略与一个比较策略。
     */
    static final class ScriptedCapability implements TrackingCapability<PlainSnapshot> {

        /**
         * 快照策略。
         */
        private final SnapshotStrategy<PlainSnapshot> snapshotStrategy;

        /**
         * 比较策略。
         */
        private final ComparisonStrategy<PlainSnapshot> comparisonStrategy;

        /**
         * 创建脚本化能力单元。
         *
         * @param snapshotStrategy   快照策略
         * @param comparisonStrategy 比较策略
         */
        ScriptedCapability(final SnapshotStrategy<PlainSnapshot> snapshotStrategy,
                           final ComparisonStrategy<PlainSnapshot> comparisonStrategy) {
            this.snapshotStrategy = Objects.requireNonNull(snapshotStrategy, "snapshotStrategy");
            this.comparisonStrategy = Objects.requireNonNull(comparisonStrategy, "comparisonStrategy");
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public SnapshotStrategy<PlainSnapshot> getSnapshotStrategy() {
            return this.snapshotStrategy;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public ComparisonStrategy<PlainSnapshot> getComparisonStrategy() {
            return this.comparisonStrategy;
        }
    }

    /**
     * 基于默认快照类型的追踪能力单元：真实组合一个 {@link SnapshotStrategy} 与一个比较策略，
     * 用于承载需要基线导出（仅支持 {@link ValueNodeSnapshot}）的失败契约场景。
     */
    static final class ValueNodeSnapshotCapability implements TrackingCapability<ValueNodeSnapshot> {

        /**
         * 快照策略。
         */
        private final SnapshotStrategy<ValueNodeSnapshot> snapshotStrategy;

        /**
         * 比较策略。
         */
        private final ComparisonStrategy<ValueNodeSnapshot> comparisonStrategy;

        /**
         * 创建能力单元。
         *
         * @param snapshotStrategy   快照策略
         * @param comparisonStrategy 比较策略
         */
        ValueNodeSnapshotCapability(final SnapshotStrategy<ValueNodeSnapshot> snapshotStrategy,
                                    final ComparisonStrategy<ValueNodeSnapshot> comparisonStrategy) {
            this.snapshotStrategy = Objects.requireNonNull(snapshotStrategy, "snapshotStrategy");
            this.comparisonStrategy = Objects.requireNonNull(comparisonStrategy, "comparisonStrategy");
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public SnapshotStrategy<ValueNodeSnapshot> getSnapshotStrategy() {
            return this.snapshotStrategy;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public ComparisonStrategy<ValueNodeSnapshot> getComparisonStrategy() {
            return this.comparisonStrategy;
        }
    }

    /**
     * 基于默认快照类型的序列化比较策略：首次比较返回预设结果，之后抛出预设异常。
     * <p>
     * 用于验证多目标计算中「前序目标已成功」也不返回部分结果——首次即失败的脚本策略无法区分
     * 「整次计算失败」与「已成功的部分被丢弃」。
     */
    static final class FailingAfterSuccessValueNodeComparisonStrategy implements ComparisonStrategy<ValueNodeSnapshot> {

        /**
         * 首次比较返回的结果。
         */
        private final List<Change> firstResult;

        /**
         * 后续比较抛出的预设异常。
         */
        private final RuntimeException failure;

        /**
         * 已执行的比较次数。
         */
        private int calls;

        /**
         * 创建序列化比较策略。
         *
         * @param firstResult 首次比较返回的结果
         * @param failure     后续比较抛出的预设异常
         */
        FailingAfterSuccessValueNodeComparisonStrategy(final List<Change> firstResult, final RuntimeException failure) {
            this.firstResult = List.copyOf(firstResult);
            this.failure = Objects.requireNonNull(failure, "failure");
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public Class<ValueNodeSnapshot> getSupportedSnapshotType() {
            return ValueNodeSnapshot.class;
        }

        /**
         * {@inheritDoc}
         * <p>
         * 首次比较返回预设结果（该目标已成功），之后抛出预设异常实例（后续目标失败）。
         */
        @Override
        public List<Change> compare(final ValueNodeSnapshot oldSnapshot, final ValueNodeSnapshot newSnapshot) {
            if (this.calls++ == 0) {
                return this.firstResult;
            }
            throw this.failure;
        }
    }

    /**
     * 基于默认快照类型的脚本化比较策略：比较时抛出预设异常。
     */
    static final class FailingValueNodeComparisonStrategy implements ComparisonStrategy<ValueNodeSnapshot> {

        /**
         * 预设失败。
         */
        private final RuntimeException failure;

        /**
         * 创建脚本化比较策略。
         *
         * @param failure 比较时抛出的预设失败
         */
        FailingValueNodeComparisonStrategy(final RuntimeException failure) {
            this.failure = Objects.requireNonNull(failure, "failure");
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public Class<ValueNodeSnapshot> getSupportedSnapshotType() {
            return ValueNodeSnapshot.class;
        }

        /**
         * {@inheritDoc}
         * <p>
         * 始终抛出预设异常实例，用于验证异常传播与基线不推进。
         */
        @Override
        public List<Change> compare(final ValueNodeSnapshot oldSnapshot, final ValueNodeSnapshot newSnapshot) {
            throw this.failure;
        }
    }
}
