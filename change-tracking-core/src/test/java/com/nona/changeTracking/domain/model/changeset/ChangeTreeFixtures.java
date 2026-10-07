package com.nona.changeTracking.domain.model.changeset;

import com.nona.changeTracking.domain.model.snapshot.ValueNode;

/**
 * 变更结果测试的公共构造夹具：按定位派生规则建立定位、原子变化与分组的可读构造入口。
 * <p>
 * 夹具只做定位与结果的装配，不隐藏被测行为：断言仍直接读取定位访问入口与载荷。
 */
public final class ChangeTreeFixtures {

    /**
     * 工具夹具不实例化。
     */
    private ChangeTreeFixtures() {
    }

    /**
     * 建立被追踪根处的定位。
     *
     * @return 根定位
     */
    public static ChangeLocation root() {
        return ChangeLocation.root();
    }

    /**
     * 建立根对象下的一级字段定位。
     *
     * @param fieldName 字段名
     * @return 字段定位
     */
    public static ChangeLocation field(final String fieldName) {
        return ChangeLocation.field(root(), fieldName);
    }

    /**
     * 建立嵌套字段定位。
     *
     * @param parent    包含位置
     * @param fieldName 字段名
     * @return 字段定位
     */
    public static ChangeLocation field(final ChangeLocation parent, final String fieldName) {
        return ChangeLocation.field(parent, fieldName);
    }

    /**
     * 建立根集合下唯一标识的集合项定位。
     *
     * @param identity 集合项标识
     * @return 集合项定位
     */
    public static ChangeLocation rootItem(final Object identity) {
        return ChangeLocation.collectionItem(root(), identity);
    }

    /**
     * 建立根对象下集合字段的唯一标识集合项定位。
     *
     * @param collectionField 集合字段名
     * @param identity        集合项标识
     * @return 集合项定位
     */
    public static ChangeLocation item(final String collectionField, final Object identity) {
        return ChangeLocation.collectionItem(field(collectionField), identity);
    }

    /**
     * 建立根对象下集合字段的集合项定位，可指定出现序后缀。
     *
     * @param collectionField 集合字段名
     * @param identity        集合项标识
     * @param occurrence      出现序，{@link ChangeLocation#NO_OCCURRENCE} 表示不加后缀
     * @return 集合项定位
     */
    public static ChangeLocation item(final String collectionField, final Object identity, final int occurrence) {
        return ChangeLocation.collectionItem(field(collectionField), identity, occurrence);
    }

    /**
     * 建立集合项内部字段的定位。
     *
     * @param collectionField 集合字段名
     * @param identity        集合项标识
     * @param fieldName       集合项内的字段名
     * @return 字段定位
     */
    public static ChangeLocation itemField(final String collectionField, final Object identity, final String fieldName) {
        return ChangeLocation.field(item(collectionField, identity), fieldName);
    }

    /**
     * 建立一个字段值变化（原子变化）。
     *
     * @param location 定位
     * @param oldValue 变更前的业务值
     * @param newValue 变更后的业务值
     * @return 字段值变化
     */
    public static ValueChange value(final ChangeLocation location, final Object oldValue, final Object newValue) {
        return new ValueChange(location, oldValue, newValue);
    }

    /**
     * 建立一个集合项新增（原子变化）。
     *
     * @param location  加入项的定位
     * @param addedItem 加入项的快照表示
     * @return 集合项新增
     */
    public static ItemAddedChange added(final ChangeLocation location, final ValueNode addedItem) {
        return new ItemAddedChange(location, addedItem);
    }

    /**
     * 建立一个集合项移除（原子变化）。
     *
     * @param location    离开项的定位
     * @param removedItem 离开项的快照表示
     * @return 集合项移除
     */
    public static ItemRemovedChange removed(final ChangeLocation location, final ValueNode removedItem) {
        return new ItemRemovedChange(location, removedItem);
    }
}
