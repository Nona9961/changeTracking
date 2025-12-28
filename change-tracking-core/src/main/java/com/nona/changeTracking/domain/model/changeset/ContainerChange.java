package com.nona.changeTracking.domain.model.changeset;

import java.util.List;

/**
 * 表示容器变更（扁平视图）。
 * <p>
 * 仅在 {@link ChangeSet#getAllChanges()} 中出现。
 *
 * @param path     相对路径。
 * @param fullPath 完整路径。
 * @param children 子变更列表。
 */
public record ContainerChange(String path, String fullPath, List<Change> children) implements Change {
}
