package com.nona.changeTracking.domain.model.changeset;

import com.nona.changeTracking.domain.model.snapshot.PrimitiveNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ContainerChange} 分组变更单元测试。
 * <p>
 * 覆盖分组的领域不变量：至少包含一项实际变化（非空分组）、每个子结果的定位处于本分组的包含结构之下、
 * 分组自身不能出现在空路径位置；以及子结果列表的只读与防御性复制。失败路径按领域不变量 3/4 与
 * 失败条件「手工构造空分组」「子结果不属于其包含位置」「非根空路径节点」设计。
 */
@DisplayName("ContainerChange 分组变更单元测试")
class ContainerChangeUnitTest {

    @Nested
    @DisplayName("合法分组")
    class ValidGroups {

        @Test
        @DisplayName("字段分组下的后代字段定位合法，子结果按给定顺序保留")
        void group_withDescendantFieldLocations_shouldRetainChildOrder() {
            final ChangeLocation items = ChangeTreeFixtures.field("items");
            final ValueChange first = ChangeTreeFixtures.value(ChangeTreeFixtures.field(items, "quantity"), 1, 2);
            final ValueChange second = ChangeTreeFixtures.value(ChangeTreeFixtures.field(items, "price"), "a", "b");

            final ContainerChange group = new ContainerChange(items, List.of(first, second));

            assertThat(group.children()).containsExactly(first, second);
            assertThat(group.location()).isEqualTo(items);
        }

        @Test
        @DisplayName("集合字段分组下的集合项定位合法（子完整路径以父路径加方括号接续）")
        void group_withCollectionItemChildren_shouldBeAccepted() {
            final ChangeLocation items = ChangeTreeFixtures.field("items");
            final ItemAddedChange added = ChangeTreeFixtures.added(ChangeTreeFixtures.item("items", 100), new PrimitiveNode("x"));

            final ContainerChange group = new ContainerChange(items, List.of(added));

            assertThat(group.children()).containsExactly(added);
        }

        @Test
        @DisplayName("单子结果的分组是合法下界（分组只要求非空）")
        void group_withExactlyOneChild_shouldBeAccepted() {
            final ChangeLocation address = ChangeTreeFixtures.field("address");
            final ValueChange street = ChangeTreeFixtures.value(ChangeTreeFixtures.field(address, "street"), "a", "b");

            final ContainerChange group = new ContainerChange(address, List.of(street));

            assertThat(group).isNotNull();
            assertThat(group.children()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("失败路径：空分组与非法定位")
    class InvalidGroups {

        @Test
        @DisplayName("分组拒绝 null 定位")
        void group_withNullLocation_shouldBeRejected() {
            assertThatThrownBy(() -> new ContainerChange(null, List.of(ChangeTreeFixtures.value(ChangeTreeFixtures.field("status"), "a", "b"))))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("分组拒绝 null 子结果列表")
        void group_withNullChildren_shouldBeRejected() {
            assertThatThrownBy(() -> new ContainerChange(ChangeTreeFixtures.field("address"), null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("手工构造空分组在构造时被拒绝（无变化只能由空比较列表表达）")
        void group_withEmptyChildren_shouldBeRejected() {
            assertThatThrownBy(() -> new ContainerChange(ChangeTreeFixtures.field("address"), List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("空路径位置的分组被拒绝（仅真实根值变化允许空路径）")
        void group_atEmptyPath_shouldBeRejected() {
            final ValueChange child = ChangeTreeFixtures.value(ChangeTreeFixtures.field("status"), "a", "b");

            assertThatThrownBy(() -> new ContainerChange(ChangeTreeFixtures.root(), List.of(child)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("子结果不属于本分组包含结构时被拒绝（同名字段路径）")
        void group_withChildOutsideItsContainment_shouldBeRejected() {
            final ValueChange outsider = ChangeTreeFixtures.value(ChangeTreeFixtures.field("name"), "a", "b");

            assertThatThrownBy(() -> new ContainerChange(ChangeTreeFixtures.field("address"), List.of(outsider)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("子结果路径仅前缀相似但不是包含关系时被拒绝（addresses 不是 address 的后代）")
        void group_withPrefixLookAlikeChild_shouldBeRejected() {
            final ValueChange outsider = ChangeTreeFixtures.value(
                    ChangeTreeFixtures.field(ChangeTreeFixtures.field("addresses"), "street"), "a", "b");

            assertThatThrownBy(() -> new ContainerChange(ChangeTreeFixtures.field("address"), List.of(outsider)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("非根空路径子结果（嵌于非根容器下的空路径节点）被拒绝")
        void group_withEmptyPathChild_shouldBeRejected() {
            final ValueChange emptyPathChild = ChangeTreeFixtures.value(ChangeTreeFixtures.root(), "a", "b");

            assertThatThrownBy(() -> new ContainerChange(ChangeTreeFixtures.field("address"), List.of(emptyPathChild)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("只读与防御性复制")
    class ReadOnlyAndDefensiveCopy {

        @Test
        @DisplayName("子结果列表不可修改")
        void children_shouldBeReadOnly() {
            final ContainerChange group = new ContainerChange(
                    ChangeTreeFixtures.field("address"),
                    List.of(ChangeTreeFixtures.value(ChangeTreeFixtures.field(ChangeTreeFixtures.field("address"), "street"), "a", "b")));

            assertThatThrownBy(() -> group.children().add(ChangeTreeFixtures.value(ChangeTreeFixtures.field("x"), "a", "b")))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> group.children().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("构造后修改入参列表不影响分组内的子结果")
        void constructor_shouldDefensivelyCopyChildren() {
            final ChangeLocation address = ChangeTreeFixtures.field("address");
            final ValueChange street = ChangeTreeFixtures.value(ChangeTreeFixtures.field(address, "street"), "a", "b");
            final List<Change> source = new ArrayList<>(List.of(street));

            final ContainerChange group = new ContainerChange(address, source);
            source.clear();

            assertThat(group.children()).containsExactly(street);
        }
    }
}
