package com.nona.changeTracking.domain.model.changeset;

import java.util.List;

/**
 * 表示容器变更（扁平视图）。
 * <p>
 * 仅在 {@link ChangeSet#getAllChanges()} 中出现，表示对象或集合内部有变更。
 * 在 {@link ChangeSet#getLeafChanges()} 中会被过滤掉。
 *
 * @param path     容器的路径。
 * @param children 子变更列表。
 */
public record ContainerChange(String path, List<Change> children) implements Change {
}
