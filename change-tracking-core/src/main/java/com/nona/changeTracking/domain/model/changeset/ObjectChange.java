package com.nona.changeTracking.domain.model.changeset;

import java.util.List;
import java.util.Objects;

/**
 * 一个值对象，用于聚合单个被修改（"Dirty"）领域对象的所有字段变更。
 * <p>
 * 它持有一个对领域对象当前实例的引用，以及一个包含所有 {@link FieldChange} 的列表。
 * <p>
 * 使用 Java Record 实现，以自动获得不可变性、构造函数、访问器以及正确的
 * {@code equals()}, {@code hashCode()}, 和 {@code toString()} 实现。
 *
 * @param object       被修改的领域对象的当前实例引用。
 * @param fieldChanges 该对象所有发生变化的字段列表。
 */
public record ObjectChange(Object object, List<FieldChange> fieldChanges) {
    /**
     * 构造函数，进行非空校验，并确保传入的字段变更列表是不可变的。
     */
    public ObjectChange {
        Objects.requireNonNull(object, "Object reference cannot be null.");
        Objects.requireNonNull(fieldChanges, "Field changes list cannot be null.");
        // 创建一个不可变的副本，以保护内部状态
        fieldChanges = List.copyOf(fieldChanges);
    }
}
