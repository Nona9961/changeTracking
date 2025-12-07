package com.nona.changeTracking.domain.model.changeset;

/**
 * 表示集合项新增（扁平视图）。
 * <p>
 * 表示在集合中新增了一个项。
 *
 * @param path      集合的路径。
 * @param addedItem 新增的项。
 */
public record ItemAddedChange(String path, Object addedItem) implements Change {
}
