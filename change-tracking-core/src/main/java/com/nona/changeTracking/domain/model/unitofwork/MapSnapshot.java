package com.nona.changeTracking.domain.model.unitofwork;

import java.util.Map;

/**
 * 一个实现了 {@link Snapshot} 接口的值对象，用于封装对象状态的深度数据表示。
 * <p>
 * 它内部持有一个 {@code Map<String, Object>}，其中键是字段名，值是字段的快照值。
 * 这个快照是通过反射等机制提取对象状态而生成的，它不是一个可执行的对象副本。
 * <p>
 * 使用 Java Record 实现，以自动获得不可变性、构造函数、访问器以及正确的
 * {@code equals()}, {@code hashCode()}, 和 {@code toString()} 实现。
 *
 * @param data 对象的深度数据表示。
 */
public record MapSnapshot(Map<String, Object> data) implements Snapshot<Map<String, Object>> {

    /**
     * {@inheritDoc}
     */
    @Override
    public Map<String, Object> getSnapshotData() {
        // Record 自动生成了名为 data() 的访问器，这里我们适配接口方法。
        return data();
    }
}
