package com.nona.changeTracking.domain.model.unitofwork;

/**
 * 一个实现了 {@link Snapshot} 接口的值对象，用于封装领域对象的深拷贝副本。
 * <p>
 * 它内部持有一个 {@link Object} 实例，该实例是通过序列化等技术创建的、
 * 与原始对象完全独立的副本。
 * <p>
 * 使用 Java Record 实现，以自动获得不可变性、构造函数、访问器以及正确的
 * {@code equals()}, {@code hashCode()}, 和 {@code toString()} 实现。
 *
 * @param data 对象的深拷贝副本。
 */
public record ObjectSnapshot(Object data) implements Snapshot<Object> {

    /**
     * {@inheritDoc}
     */
    @Override
    public Object getSnapshotData() {
        // Record 自动生成了名为 data() 的访问器，这里我们适配接口方法。
        return data();
    }
}
