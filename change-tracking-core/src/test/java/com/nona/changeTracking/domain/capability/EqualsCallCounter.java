package com.nona.changeTracking.domain.capability;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 外置的 {@code equals} 调用计数器。
 * <p>
 * 计数载体属于测试侧：比较策略本身不提供任何计量接口，叶子语义比较的次数由计数样本把每次
 * {@code equals} 调用写入本对象来观察。计数器由测试用例持有并按用例重建，比较开始前清零，
 * 因此不跨用例累积。
 * <p>
 * 本类不引用 {@code internal} 包，也不进入运行时公共 API。
 */
final class EqualsCallCounter {

    /**
     * 累计的 {@code equals} 调用次数。
     */
    private final AtomicLong calls;

    /**
     * 创建一个已清零的计数器。
     */
    EqualsCallCounter() {
        this.calls = new AtomicLong();
    }

    /**
     * 记一次 {@code equals} 调用。
     */
    void increment() {
        this.calls.incrementAndGet();
    }

    /**
     * 返回累计调用次数。
     *
     * @return 累计的 {@code equals} 调用次数。
     */
    long count() {
        return this.calls.get();
    }

    /**
     * 清零计数器，供比较开始前复位。
     */
    void reset() {
        this.calls.set(0L);
    }

    /**
     * 返回调用次数的文本形式，便于规模样本的记录与断言。
     *
     * @return 累计调用次数。
     */
    @Override
    public String toString() {
        return Objects.toString(this.calls.get());
    }
}
