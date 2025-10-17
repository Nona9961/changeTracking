package com.nona.changeTracking.domain.model.changeset;

import java.util.Objects;

/**
 * 表示单个被追踪对象的整体变更。
 * <p>
 * 它持有一个代表所有细粒度变更的 {@link ChangeNode} 树的根节点。
 * 这是一个 record，因为它是一个不可变的数据载体。
 *
 * @param target     被追踪的、发生变更的对象实例。
 * @param changeTree 描述该对象所有内部变更的树的根节点。
 */
public record ObjectChange(Object target, ChangeNode changeTree) {
    public ObjectChange {
        Objects.requireNonNull(target, "Target object cannot be null.");
        Objects.requireNonNull(changeTree, "Change tree cannot be null.");
    }
}
