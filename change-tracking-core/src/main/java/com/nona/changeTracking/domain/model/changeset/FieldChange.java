package com.nona.changeTracking.domain.model.changeset;

import java.util.Objects;

/**
 * 一个值对象，用于表示领域对象中单个字段的状态变更。
 * <p>
 * 它封装了字段的名称、旧值和新值。
 * <p>
 * 使用 Java Record 实现，以自动获得不可变性、构造函数、访问器以及正确的
 * {@code equals()}, {@code hashCode()}, 和 {@code toString()} 实现。
 *
 * @param fieldName 发生变更的字段名称。
 * @param oldValue  字段的旧值（变更前的值）。
 * @param newValue  字段的新值（变更后的值）。
 */
public record FieldChange(String fieldName, Object oldValue, Object newValue) {
    /**
     * 构造函数，对字段名进行非空校验。
     */
    public FieldChange {
        Objects.requireNonNull(fieldName, "Field name cannot be null.");
    }
}
