package com.nona.changeTracking.domain.model.changeset;

import java.util.List;
import java.util.Objects;

/**
 * 表示变更分组：某个结构位置包含哪些变化。
 * <p>
 * 分组自身不代表一项可执行的变化，它是结果树中的组织节点；原子变化通过 {@link ValueChange}、
 * {@link ObjectFieldChange}、{@link ItemAddedChange}、{@link ItemRemovedChange} 表达。
 * <p>
 * 构造即结果构建入口，须满足领域不变量：子结果非空；每个子结果的定位必须处于本分组的包含结构之下
 * （子结果的完整路径以本分组的完整路径为前缀，并以字段分隔符 {@code .} 或集合项前缀 {@code [} 接续）；
 * 分组自身不能位于空路径位置（空路径只保留真实根值变化一种形态，由原子变化表达）。
 * 违反上述不变量的分组在构造时被拒绝，不延迟到视图访问时才失败。
 *
 * @param location 分组的定位
 * @param children 分组内的子结果，非空
 */
public record ContainerChange(ChangeLocation location, List<Change> children) implements Change {

    /**
     * 紧凑构造器：拒绝空分组，防御性复制子结果列表并校验包含定位一致。
     *
     * @param location 分组的定位
     * @param children 分组内的子结果，非空
     */
    public ContainerChange {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(children, "children");
        if (children.isEmpty()) {
            throw new IllegalArgumentException("A change container must hold at least one child change.");
        }
        final String parentPath = location.fullPath();
        if (parentPath.isEmpty()) {
            throw new IllegalArgumentException("A change container must not be located at the empty root path.");
        }
        for (final Change child : children) {
            Objects.requireNonNull(child, "child");
            final String childPath = child.fullPath();
            if (!isContainedPath(childPath, parentPath)) {
                throw new IllegalArgumentException(
                        "Child location " + childPath + " is not contained by " + parentPath + '.');
            }
        }
        children = List.copyOf(children);
    }

    /**
     * 判断子完整路径是否处于父完整路径的包含结构之下。
     * <p>
     * 以路径段为界：子路径必须是父路径后接字段分隔符（{@code .}）或集合项前缀（{@code [}），
     * 因此仅文本前缀相似的路径（如 {@code addresses.street} 对 {@code address}）不被视为包含。
     *
     * @param childPath  子结果的完整路径
     * @param parentPath 本分组的完整路径，非空
     * @return 子结果属于本分组包含结构时返回 true
     */
    private static boolean isContainedPath(final String childPath, final String parentPath) {
        return childPath.startsWith(parentPath + '.') || childPath.startsWith(parentPath + '[');
    }
}
