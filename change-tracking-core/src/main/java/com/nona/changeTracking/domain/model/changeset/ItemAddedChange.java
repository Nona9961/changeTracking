package com.nona.changeTracking.domain.model.changeset;

import com.nona.changeTracking.domain.model.snapshot.ValueNode;

/**
 * 表示集合项新增：一次原子变化，载荷是加入项的只读快照表示。
 * <p>
 * 加入集合不必然代表创建实体——本类型只表达集合成员关系的变化，业务含义由消费方解释。
 * 定位是加入项自身的位置（集合字段位置下的集合项位置）。
 *
 * @param location  加入项的定位
 * @param addedItem 加入项的 ValueNode 表示
 */
public record ItemAddedChange(ChangeLocation location, ValueNode addedItem) implements Change {
}
