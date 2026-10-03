package com.nona.changeTracking.domain.capability;

import java.util.Objects;

/**
 * 计数 {@code equals} 调用的不可变值样本（T03 / AC03.5）。
 * <p>
 * 样本类型按 {@code code} 判定相等，每次 {@code equals} 调用先把调用次数写入外置的
 * {@link EqualsCallCounter}。比较用例让旧、新快照侧各自持有<b>独立且相等</b>的实例
 * （{@link Objects#equals(Object, Object)} 对同一实例会短路、不调用 {@code equals}），
 * 从而把叶子语义比较暴露为可断言的调用次数。
 * <p>
 * 本类不引用 {@code internal} 包；值是业务值，运行时公共 API 不增加任何计量接口。
 */
final class EqualsCountingValue {

    /**
     * 判定相等性的值编码。
     */
    private final String code;

    /**
     * 接收本次 {@code equals} 调用计数的外置计数器。
     */
    private final EqualsCallCounter counter;

    /**
     * 创建一个计数样本值。
     *
     * @param code    判定相等性的值编码，不能为 null。
     * @param counter 接收 {@code equals} 调用计数的外置计数器，不能为 null。
     * @throws NullPointerException 如果 code 或 counter 为 null。
     */
    EqualsCountingValue(final String code, final EqualsCallCounter counter) {
        this.code = Objects.requireNonNull(code, "code");
        this.counter = Objects.requireNonNull(counter, "counter");
    }

    /**
     * 返回值编码。
     *
     * @return 判定相等性的值编码。
     */
    String code() {
        return this.code;
    }

    /**
     * 记录一次调用后按值编码比较。
     *
     * @param other 待比较对象。
     * @return 值编码相同返回 true。
     */
    @Override
    public boolean equals(final Object other) {
        this.counter.increment();
        return other instanceof EqualsCountingValue that && this.code.equals(that.code);
    }

    /**
     * 按值编码计算哈希。
     *
     * @return 值编码的哈希。
     */
    @Override
    public int hashCode() {
        return this.code.hashCode();
    }

    /**
     * 返回值编码文本；本样本不把 {@code toString} 用作计数入口。
     *
     * @return 值编码。
     */
    @Override
    public String toString() {
        return this.code;
    }
}
