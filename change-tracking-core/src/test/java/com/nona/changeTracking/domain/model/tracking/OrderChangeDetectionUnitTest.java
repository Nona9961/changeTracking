package com.nona.changeTracking.domain.model.tracking;

import com.nona.changeTracking.domain.model.changeset.Change;
import com.nona.changeTracking.domain.model.changeset.ChangeSet;
import com.nona.changeTracking.domain.model.changeset.ContainerChange;
import com.nona.changeTracking.domain.model.changeset.ItemAddedChange;
import com.nona.changeTracking.domain.model.changeset.ItemRemovedChange;
import com.nona.changeTracking.domain.model.changeset.ObjectFieldChange;
import com.nona.changeTracking.domain.model.changeset.ValueChange;
import com.nona.changeTracking.domain.model.snapshot.NullNode;
import com.nona.changeTracking.domain.model.snapshot.ObjectNode;
import com.nona.changeTracking.internal.capability.DefaultTrackingCapabilityProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 订单净差异与输出顺序单元测试。
 * <p>
 * 用真实默认装配（provider → capability → 反射快照 → 默认比较策略 → 追踪器）验证：订单字段修改、成员内部
 * 字段修改、成员加入与成员移出分别得到正确的位置、类型与载荷；对象整体赋值与清空是原子变化，不展开载荷
 * 内部操作；A 改为 B 再恢复 A 时无净变化；字段按声明序输出，集合项按匹配组顺序（旧侧标识先、新侧后）输出；
 * 数组按值语义报告变化；重复标识按出现序后缀区分。视图访问不推进基线。
 */
@DisplayName("订单净差异与输出顺序单元测试")
class OrderChangeDetectionUnitTest {

    /**
     * 每个用例重建的默认 provider（含业务标识注册），避免配置串用。
     */
    private DefaultTrackingCapabilityProvider provider;

    @BeforeEach
    void setUp() {
        provider = new DefaultTrackingCapabilityProvider();
        provider.withIdentifier(LineItem.class, item -> item.id);
    }

    /**
     * 建立默认装配的变更检测器。
     *
     * @return 变更检测器
     */
    private ChangeTracker newTracker() {
        return new ChangeTracker(provider.create());
    }

    @Nested
    @DisplayName("单目标净差异")
    class NetDifference {

        @Test
        @DisplayName("订单字段修改：得到字段值变化及其位置与前后值")
        void orderFieldChange_shouldReportValueChangeAtItsLocation() {
            final ChangeTracker tracker = newTracker();
            final Order order = new Order();
            tracker.track(order);
            order.status = "PAID";

            final ChangeSet changeSet = tracker.calculateChanges();

            assertThat(changeSet.getLeafChanges()).hasSize(1);
            final ValueChange statusChange = (ValueChange) changeSet.getLeafChanges().get(0);
            assertThat(statusChange.fullPath()).isEqualTo("status");
            assertThat(statusChange.relativePath()).isEqualTo("status");
            assertThat(statusChange.fieldName()).isEqualTo("status");
            assertThat(statusChange.collectionFieldName()).isNull();
            assertThat(statusChange.isParentCollection()).isFalse();
            assertThat(statusChange.oldValue()).isEqualTo("CREATED");
            assertThat(statusChange.newValue()).isEqualTo("PAID");
        }

        @Test
        @DisplayName("成员内部字段修改：定位到集合项内字段，集合归属为 items")
        void itemFieldChange_shouldLocateInsideTheCollectionItem() {
            final ChangeTracker tracker = newTracker();
            final Order order = new Order();
            order.items.add(new LineItem(7L, 1));
            tracker.track(order);
            order.items.get(0).quantity = 2;

            final ChangeSet changeSet = tracker.calculateChanges();

            assertThat(changeSet.getLeafChanges()).hasSize(1);
            final ValueChange quantityChange = (ValueChange) changeSet.getLeafChanges().get(0);
            assertThat(quantityChange.fullPath()).isEqualTo("items[7].quantity");
            assertThat(quantityChange.relativePath()).isEqualTo("quantity");
            assertThat(quantityChange.fieldName()).isEqualTo("quantity");
            assertThat(quantityChange.collectionFieldName()).isEqualTo("items");
            assertThat(quantityChange.oldValue()).isEqualTo(1);
            assertThat(quantityChange.newValue()).isEqualTo(2);
            assertThat(changeSet.getAllChanges()).extracting(Change::fullPath)
                    .containsExactly("items", "items[7]", "items[7].quantity");
        }

        @Test
        @DisplayName("成员加入：集合项新增携带加入项快照，不额外报告数量或集合字段变化")
        void itemAdded_shouldReportMembershipChangeOnly() {
            final ChangeTracker tracker = newTracker();
            final Order order = new Order();
            tracker.track(order);
            order.items.add(new LineItem(9L, 1));

            final ChangeSet changeSet = tracker.calculateChanges();

            assertThat(changeSet.getLeafChanges()).hasSize(1);
            final ItemAddedChange added = (ItemAddedChange) changeSet.getLeafChanges().get(0);
            assertThat(added.fullPath()).isEqualTo("items[9]");
            assertThat(added.relativePath()).isEqualTo("[9]");
            assertThat(added.fieldName()).isNull();
            assertThat(added.collectionFieldName()).isEqualTo("items");
            assertThat(added.isParentCollection()).isTrue();
            assertThat(added.addedItem()).isInstanceOf(ObjectNode.class);
            assertThat(((ObjectNode) added.addedItem()).identifier()).isEqualTo(9L);
            assertThat(changeSet.getAllChanges().get(0)).isInstanceOf(ContainerChange.class);
        }

        @Test
        @DisplayName("成员移出：集合项移除携带离开项快照")
        void itemRemoved_shouldReportMembershipChangeOnly() {
            final ChangeTracker tracker = newTracker();
            final Order order = new Order();
            order.items.add(new LineItem(3L, 1));
            tracker.track(order);
            order.items.clear();

            final ChangeSet changeSet = tracker.calculateChanges();

            assertThat(changeSet.getLeafChanges()).hasSize(1);
            final ItemRemovedChange removed = (ItemRemovedChange) changeSet.getLeafChanges().get(0);
            assertThat(removed.fullPath()).isEqualTo("items[3]");
            assertThat(removed.collectionFieldName()).isEqualTo("items");
            assertThat(removed.isParentCollection()).isTrue();
            assertThat(removed.removedItem()).isInstanceOf(ObjectNode.class);
        }

        @Test
        @DisplayName("对象整体赋值：null 变为对象是原子变化，载荷为两侧快照且不展开内部操作")
        void objectAssignment_shouldBeAtomic() {
            final ChangeTracker tracker = newTracker();
            final Order order = new Order();
            tracker.track(order);
            order.address = new Address("Main St");

            final ChangeSet changeSet = tracker.calculateChanges();

            assertThat(changeSet.getLeafChanges()).hasSize(1);
            final ObjectFieldChange assigned = (ObjectFieldChange) changeSet.getLeafChanges().get(0);
            assertThat(assigned.fullPath()).isEqualTo("address");
            assertThat(assigned.oldNode()).isInstanceOf(NullNode.class);
            assertThat(assigned.newNode()).isInstanceOf(ObjectNode.class);
        }

        @Test
        @DisplayName("对象清空：对象变为 null 是原子变化")
        void objectCleared_shouldBeAtomic() {
            final ChangeTracker tracker = newTracker();
            final Order order = new Order();
            order.address = new Address("Main St");
            tracker.track(order);
            order.address = null;

            final ChangeSet changeSet = tracker.calculateChanges();

            assertThat(changeSet.getLeafChanges()).hasSize(1);
            final ObjectFieldChange cleared = (ObjectFieldChange) changeSet.getLeafChanges().get(0);
            assertThat(cleared.fullPath()).isEqualTo("address");
            assertThat(cleared.oldNode()).isInstanceOf(ObjectNode.class);
            assertThat(cleared.newNode()).isInstanceOf(NullNode.class);
        }

        @Test
        @DisplayName("A 改为 B 再恢复 A：无净变化，不创建 ObjectChange")
        void changeRestored_shouldHaveNoNetDifference() {
            final ChangeTracker tracker = newTracker();
            final Order order = new Order();
            tracker.track(order);
            order.status = "PAID";
            order.status = "CREATED";

            final ChangeSet changeSet = tracker.calculateChanges();

            assertThat(changeSet.isEmpty()).isTrue();
            assertThat(changeSet.changes()).isEmpty();
            assertThat(changeSet.getAllChanges()).isEmpty();
            assertThat(changeSet.getLeafChanges()).isEmpty();
        }

        @Test
        @DisplayName("数组字段按值语义报告变化，载荷为两侧数组实例")
        void arrayField_shouldReportValueChangeWithArrayPayloads() {
            final ChangeTracker tracker = newTracker();
            final Order order = new Order();
            order.items.add(new LineItem(7L, 1));
            tracker.track(order);
            order.items.get(0).codes = new int[]{3, 2, 1};

            final ChangeSet changeSet = tracker.calculateChanges();

            assertThat(changeSet.getLeafChanges()).hasSize(1);
            final ValueChange codesChange = (ValueChange) changeSet.getLeafChanges().get(0);
            assertThat(codesChange.fullPath()).isEqualTo("items[7].codes");
            assertThat(codesChange.oldValue()).isEqualTo(new int[]{1, 2, 3});
            assertThat(codesChange.newValue()).isEqualTo(new int[]{3, 2, 1});
        }
    }

    @Nested
    @DisplayName("输出顺序契约")
    class OutputOrder {

        @Test
        @DisplayName("字段变化按声明序输出（id 在 status 之前）")
        void fieldChanges_shouldFollowDeclarationOrder() {
            final ChangeTracker tracker = newTracker();
            final Order order = new Order();
            tracker.track(order);
            order.status = "PAID";
            order.id = 42L;

            final ChangeSet changeSet = tracker.calculateChanges();

            assertThat(changeSet.getLeafChanges()).extracting(Change::fullPath)
                    .containsExactly("id", "status");
        }

        @Test
        @DisplayName("集合项变化按匹配组顺序输出：旧侧标识先、新侧独有在后")
        void itemChanges_shouldFollowMatchGroupOrder() {
            final ChangeTracker tracker = newTracker();
            final Order order = new Order();
            order.items.add(new LineItem(1L, 1));
            order.items.add(new LineItem(2L, 1));
            tracker.track(order);
            order.items.get(0).quantity = 2;
            order.items.remove(1);
            order.items.add(new LineItem(3L, 1));

            final ChangeSet changeSet = tracker.calculateChanges();

            assertThat(changeSet.getLeafChanges()).extracting(Change::fullPath)
                    .containsExactly("items[1].quantity", "items[2]", "items[3]");
            assertThat(changeSet.getLeafChanges().get(0)).isInstanceOf(ValueChange.class);
            assertThat(changeSet.getLeafChanges().get(1)).isInstanceOf(ItemRemovedChange.class);
            assertThat(changeSet.getLeafChanges().get(2)).isInstanceOf(ItemAddedChange.class);
        }

        @Test
        @DisplayName("同一匹配组内先输出配对项变化、再输出多余新项")
        void matchedPairsInGroup_shouldComeBeforeSurplusItems() {
            final ChangeTracker tracker = newTracker();
            final Order order = new Order();
            order.items.add(new LineItem(1L, 7));
            order.items.add(new LineItem(1L, 8));
            tracker.track(order);
            order.items.get(1).quantity = 9;
            order.items.add(new LineItem(1L, 10));

            final ChangeSet changeSet = tracker.calculateChanges();

            assertThat(changeSet.getLeafChanges()).extracting(Change::fullPath)
                    .containsExactly("items[1#2].quantity", "items[1#3]");
            assertThat(changeSet.getLeafChanges().get(0)).isInstanceOf(ValueChange.class);
            assertThat(changeSet.getLeafChanges().get(1)).isInstanceOf(ItemAddedChange.class);
        }

        @Test
        @DisplayName("同一匹配组内先配对比较、后移除多余旧项")
        void duplicateIdentifiers_shouldPairByOccurrenceAndKeepTheSuffix() {
            final ChangeTracker tracker = newTracker();
            final Order order = new Order();
            order.items.add(new LineItem(1L, 1));
            order.items.add(new LineItem(1L, 1));
            tracker.track(order);
            order.items.remove(1);

            final ChangeSet changeSet = tracker.calculateChanges();

            assertThat(changeSet.getLeafChanges()).extracting(Change::fullPath)
                    .containsExactly("items[1#2]");
            assertThat(changeSet.getLeafChanges().get(0)).isInstanceOf(ItemRemovedChange.class);
        }

        @Test
        @DisplayName("重复标识的配对变化带出现序后缀")
        void duplicateIdentifiers_changeOnFirstOccurrence_shouldCarryTheSuffix() {
            final ChangeTracker tracker = newTracker();
            final Order order = new Order();
            order.items.add(new LineItem(1L, 1));
            order.items.add(new LineItem(1L, 9));
            tracker.track(order);
            order.items.get(0).quantity = 5;

            final ChangeSet changeSet = tracker.calculateChanges();

            assertThat(changeSet.getLeafChanges()).extracting(Change::fullPath)
                    .containsExactly("items[1#1].quantity");
        }
    }

    @Nested
    @DisplayName("查询无副作用")
    class SideEffectFreeQueries {

        @Test
        @DisplayName("视图访问与重复计算不推进基线：同一变更被重复报告")
        void repeatedCalculation_shouldKeepReportingTheSameChange() {
            final ChangeTracker tracker = newTracker();
            final Order order = new Order();
            tracker.track(order);
            order.status = "PAID";

            final ChangeSet first = tracker.calculateChanges();
            final List<Change> firstLeaves = first.getLeafChanges();
            first.getAllChanges();
            final ChangeSet second = tracker.calculateChanges();

            assertThat(second.getLeafChanges()).hasSize(firstLeaves.size());
            assertThat(second.getLeafChanges().get(0).fullPath()).isEqualTo("status");
        }
    }

    /**
     * 订单探针：字段声明序为 id、status、address、items。
     */
    static final class Order {

        /**
         * 业务标识。
         */
        Long id = 1L;

        /**
         * 订单状态。
         */
        String status = "CREATED";

        /**
         * 收货地址，可为 null。
         */
        Address address;

        /**
         * 订单行项目。
         */
        List<LineItem> items = new ArrayList<>();
    }

    /**
     * 地址探针（复杂对象，按结构比较）。
     */
    static final class Address {

        /**
         * 街道。
         */
        String street;

        /**
         * 创建地址。
         *
         * @param street 街道
         */
        Address(final String street) {
            this.street = street;
        }
    }

    /**
     * 行项目探针：字段声明序为 id、quantity、codes。
     */
    static final class LineItem {

        /**
         * 业务标识（比较时用于集合项匹配）。
         */
        Long id;

        /**
         * 数量。
         */
        int quantity;

        /**
         * 编码数组（值语义）。
         */
        int[] codes = new int[]{1, 2, 3};

        /**
         * 创建行项目。
         *
         * @param id       业务标识
         * @param quantity 数量
         */
        LineItem(final Long id, final int quantity) {
            this.id = id;
            this.quantity = quantity;
        }
    }
}
