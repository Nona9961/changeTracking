package com.nona.changeTracking.domain.model.changeset;

import java.util.List;

/**
 * 表示容器节点的变更（树形视图）。
 * <p>
 * 容器节点本身不代表具体的值变更，而是包含一组子变更节点。
 * 用于表示对象或集合内部的变更层级结构。
 *
 * @param path     容器的路径。
 * @param children 子变更节点列表。
 */
public record ContainerChangeNode(String path, List<ChangeNode> children) implements ChangeNode {

    /**
     * 紧凑构造器：防御性拷贝子变更列表，保证节点构造后不可变。
     *
     * @param path     容器的路径。
     * @param children 子变更节点列表。
     */
    public ContainerChangeNode {
        children = List.copyOf(children);
    }
}
