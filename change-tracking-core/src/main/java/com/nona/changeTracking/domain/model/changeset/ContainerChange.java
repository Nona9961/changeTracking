package com.nona.changeTracking.domain.model.changeset;

import java.util.List;

/**
 * 表示变更分组：某个结构位置包含哪些变化。
 * <p>
 * 分组自身不代表一项可执行的变化，它是结果树中的组织节点；原子变化通过 {@link ValueChange}、
 * {@link ObjectFieldChange}、{@link ItemAddedChange}、{@link ItemRemovedChange} 表达。
 * <p>
 * 构造即结果构建入口，须满足领域不变量：子结果非空；每个子结果的定位必须处于本分组的包含结构之下
 * （子结果的完整路径非空，且以本分组的完整路径为前缀）；分组自身不能出现在非根的空路径位置。
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
        System.err.println("[red] ContainerChange.<init> not implemented");
        throw new UnsupportedOperationException("ContainerChange.<init> is not implemented yet");
    }
}
