package com.nona.changeTracking.domain.model.changeset;

/**
 * 变更的密封接口：一次基线比较产出的统一变更描述。
 * <p>
 * 统一模型只保留这一套变更类型（五个实现，见 {@code permits}），由比较策略直接产出：
 * 原子变化（字段值变化、整体替换、集合项新增、集合项移除）与变更分组（容器）都表达为
 * {@link Change}，通过既有五个具体类型区分；不再存在第二套中间节点层次，也不再需要
 * 节点到节点的转换链路。
 * <p>
 * 每个变更携带一个不可变的 {@link ChangeLocation} 定位，下列定位访问入口都是对它的薄委托，
 * 语义由定位对象集中拥有：完整路径在所有入口下固定（{@link #path()} 与 {@link #fullPath()}
 * 含义一致，保留 {@code path} 作为同义入口），相对路径相对真实包含节点，字段名、集合归属与
 * 「直接包含者是否为集合」在所有入口下一致。
 *
 * @see ValueChange 基本值字段变更
 * @see ObjectFieldChange 对象/集合字段整体替换
 * @see ContainerChange 变更分组
 * @see ItemAddedChange 集合项新增
 * @see ItemRemovedChange 集合项移除
 */
public sealed interface Change permits ValueChange, ObjectFieldChange, ContainerChange, ItemAddedChange, ItemRemovedChange {

    /**
     * 返回此变更的定位。
     *
     * @return 不可变定位对象
     */
    ChangeLocation location();

    /**
     * 返回此变更的路径：与 {@link #fullPath()} 含义一致（完整路径），保留本入口作为既有名称的同义入口。
     *
     * @return 完整路径
     */
    default String path() {
        return location().fullPath();
    }

    /**
     * 返回此变更相对被追踪根对象的完整路径。
     *
     * @return 完整路径
     */
    default String fullPath() {
        return location().fullPath();
    }

    /**
     * 返回此变更相对真实包含节点的局部路径。
     *
     * @return 相对路径
     */
    default String relativePath() {
        return location().relativePath();
    }

    /**
     * 返回此变更定位对应的字段名。
     *
     * @return 字段名，直接集合项为 null
     */
    default String fieldName() {
        return location().fieldName();
    }

    /**
     * 返回此变更所属的最近集合字段名。
     *
     * @return 集合字段名，不在集合内时为 null
     */
    default String collectionFieldName() {
        return location().collectionFieldName();
    }

    /**
     * 判断此变更的直接包含者是否为集合。
     *
     * @return 直接包含者是集合时返回 true
     */
    default boolean isParentCollection() {
        return location().isParentCollection();
    }
}
