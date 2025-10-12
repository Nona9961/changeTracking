package com.nona.changeTracking.domain.model.changeset;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.util.List;
import java.util.Objects;

/**
 * 一个顶层值对象，封装了一次变更检测操作后产生的所有结果。
 * <p>
 * 它包含了被识别为“新”（New）、“脏”（Dirty）和“已移除”（Removed）的对象集合。
 * 这个对象是不可变的，一旦创建，其内容就不能被修改。
 * <p>
 * 此类使用 Lombok 的注解来生成大部分样板代码，但手动实现了 {@code toString()} 方法，
 * 以提供一个更简洁、对日志更友好的输出。
 */
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Getter
@EqualsAndHashCode
public final class ChangeSet {

    private final List<Object> newObjects;
    private final List<ObjectChange> dirtyObjects;
    private final List<Object> removedObjects;

    /**
     * 创建一个空的 ChangeSet 实例。
     *
     * @return 一个不包含任何变更的 ChangeSet。
     */
    public static ChangeSet empty() {
        return new ChangeSet(List.of(), List.of(), List.of());
    }

    /**
     * 静态工厂方法，用于创建 ChangeSet 的新实例。
     * <p>
     * 这个方法是创建 ChangeSet 的首选方式，因为它能确保内部集合的不可变性。
     *
     * @param newObjects     新追踪的（"New"）对象列表。
     * @param dirtyObjects   状态被修改的（"Dirty"）对象的变更详情列表。
     * @param removedObjects 被标记为移除的（"Removed"）对象列表。
     * @return 一个新的、不可变的 ChangeSet 实例。
     */
    public static ChangeSet of(
            final List<Object> newObjects,
            final List<ObjectChange> dirtyObjects,
            final List<Object> removedObjects) {
        Objects.requireNonNull(newObjects, "New objects list cannot be null.");
        Objects.requireNonNull(dirtyObjects, "Dirty objects list cannot be null.");
        Objects.requireNonNull(removedObjects, "Removed objects list cannot be null.");

        return new ChangeSet(
                List.copyOf(newObjects),
                List.copyOf(dirtyObjects),
                List.copyOf(removedObjects)
        );
    }

    /**
     * 检查此变更集是否不包含任何变更。
     *
     * @return 如果所有列表都为空，则返回 true，否则返回 false。
     */
    public boolean isEmpty() {
        return newObjects.isEmpty() && dirtyObjects.isEmpty() && removedObjects.isEmpty();
    }

    /**
     * 返回此 ChangeSet 的字符串表示形式。
     * <p>
     * 为了避免在日志中输出大量数据，此实现只包含每个集合的大小，
     * 而不是集合的实际内容。
     *
     * @return ChangeSet 的简洁字符串表示。
     */
    @Override
    public String toString() {
        return "ChangeSet{" +
               "newObjects=" + newObjects.size() +
               ", dirtyObjects=" + dirtyObjects.size() +
               ", removedObjects=" + removedObjects.size() +
               '}';
    }
}
