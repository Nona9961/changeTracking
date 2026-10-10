package com.nona.changeTracking.change;

import com.nona.changeTracking.snapshot.ValueNode;

/**
 * 表示集合项移除：一次原子变化，载荷是离开项的只读快照表示。
 * <p>
 * 移出集合不必然代表删除实体——本类型只表达集合成员关系的变化，业务含义由消费方解释。
 * 定位是离开项自身的位置（集合字段位置下的集合项位置）。
 *
 * @param location    离开项的定位
 * @param removedItem 离开项的 ValueNode 表示
 */
public record ItemRemovedChange(ChangeLocation location, ValueNode removedItem) implements Change {
}
