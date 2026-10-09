package com.nona.changeTracking.domain.model.changeset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ChangeLocation} 定位值对象单元测试。
 * <p>
 * 覆盖定位派生规则（根、字段、集合项、嵌套同名集合与重复标识）、边界与相邻值（出现序 0/1/2、
 * 深链路径、null 标识）、失败路径（空/非法输入、非法包含关系）以及值语义与「不持业务对象」约束。
 * 期望值取需求既定定位语义示例表。
 */
@DisplayName("ChangeLocation 定位值对象单元测试")
class ChangeLocationUnitTest {

    @Nested
    @DisplayName("根与字段定位")
    class RootAndFieldLocation {

        @Test
        @DisplayName("根定位：空完整路径、空相对路径、无字段名与集合归属、直接包含者不是集合")
        void root_shouldHaveEmptyPathsWithoutFieldOrCollectionContext() {
            final ChangeLocation root = ChangeLocation.root();

            assertThat(root.fullPath()).isEmpty();
            assertThat(root.relativePath()).isEmpty();
            assertThat(root.fieldName()).isNull();
            assertThat(root.collectionFieldName()).isNull();
            assertThat(root.isParentCollection()).isFalse();
        }

        @Test
        @DisplayName("根对象的一级字段：完整路径与相对路径同为字段名，集合归属为空")
        void field_atRoot_shouldUseFieldNameAsBothPaths() {
            final ChangeLocation location = ChangeLocation.field(ChangeLocation.root(), "status");

            assertThat(location.fullPath()).isEqualTo("status");
            assertThat(location.relativePath()).isEqualTo("status");
            assertThat(location.fieldName()).isEqualTo("status");
            assertThat(location.collectionFieldName()).isNull();
            assertThat(location.isParentCollection()).isFalse();
        }

        @Test
        @DisplayName("嵌套字段：完整路径含包含节点的路径，相对路径只是本段字段名")
        void nestedField_shouldBehaveAsRelativePathAgainstItsContainer() {
            final ChangeLocation address = ChangeLocation.field(ChangeLocation.root(), "address");
            final ChangeLocation street = ChangeLocation.field(address, "street");

            assertThat(street.fullPath()).isEqualTo("address.street");
            assertThat(street.relativePath()).isEqualTo("street");
            assertThat(street.fieldName()).isEqualTo("street");
            assertThat(street.collectionFieldName()).isNull();
            assertThat(street.isParentCollection()).isFalse();
        }
    }

    @Nested
    @DisplayName("集合项定位（既定语义示例表）")
    class CollectionItemLocation {

        @Test
        @DisplayName("集合字段下的集合项：items[100]，字段名为空、集合归属 items、直接父级为集合")
        void itemUnderCollectionField_shouldCarryTheCollectionFieldName() {
            final ChangeLocation location = ChangeLocation.collectionItem(ChangeLocation.field(ChangeLocation.root(), "items"), 100);

            assertThat(location.fullPath()).isEqualTo("items[100]");
            assertThat(location.relativePath()).isEqualTo("[100]");
            assertThat(location.fieldName()).isNull();
            assertThat(location.collectionFieldName()).isEqualTo("items");
            assertThat(location.isParentCollection()).isTrue();
        }

        @Test
        @DisplayName("集合项内的字段：items[200].name，字段名 name、集合归属 items、直接父级不是集合")
        void fieldInsideCollectionItem_shouldInheritTheCollectionFieldName() {
            final ChangeLocation item = ChangeLocation.collectionItem(ChangeLocation.field(ChangeLocation.root(), "items"), 200);
            final ChangeLocation location = ChangeLocation.field(item, "name");

            assertThat(location.fullPath()).isEqualTo("items[200].name");
            assertThat(location.relativePath()).isEqualTo("name");
            assertThat(location.fieldName()).isEqualTo("name");
            assertThat(location.collectionFieldName()).isEqualTo("items");
            assertThat(location.isParentCollection()).isFalse();
        }

        @Test
        @DisplayName("嵌套同名集合：items[200].subItems[101].name 的集合归属是最近的 subItems")
        void nestedCollectionInsideItem_shouldResolveTheNearestCollectionFieldName() {
            final ChangeLocation items = ChangeLocation.field(ChangeLocation.root(), "items");
            final ChangeLocation item = ChangeLocation.collectionItem(items, 200);
            final ChangeLocation subItems = ChangeLocation.field(item, "subItems");
            final ChangeLocation subItem = ChangeLocation.collectionItem(subItems, 101);
            final ChangeLocation location = ChangeLocation.field(subItem, "name");

            assertThat(location.fullPath()).isEqualTo("items[200].subItems[101].name");
            assertThat(location.relativePath()).isEqualTo("name");
            assertThat(location.fieldName()).isEqualTo("name");
            assertThat(location.collectionFieldName()).isEqualTo("subItems");
            assertThat(location.isParentCollection()).isFalse();
        }

        @Test
        @DisplayName("根集合的集合项：完整路径与相对路径同为 [100]，不伪造集合字段名")
        void itemOfRootCollection_shouldNotInventACollectionFieldName() {
            final ChangeLocation location = ChangeLocation.collectionItem(ChangeLocation.root(), 100);

            assertThat(location.fullPath()).isEqualTo("[100]");
            assertThat(location.relativePath()).isEqualTo("[100]");
            assertThat(location.fieldName()).isNull();
            assertThat(location.collectionFieldName()).isNull();
            assertThat(location.isParentCollection()).isTrue();
        }

        @Test
        @DisplayName("嵌套集合项的相对路径只含自身段，完整路径含全部包含节点")
        void nestedCollectionItem_shouldKeepOnlyItsOwnSegmentAsRelativePath() {
            final ChangeLocation items = ChangeLocation.field(ChangeLocation.root(), "items");
            final ChangeLocation item = ChangeLocation.collectionItem(items, 200);
            final ChangeLocation subItems = ChangeLocation.field(item, "subItems");
            final ChangeLocation subItem = ChangeLocation.collectionItem(subItems, 101);

            assertThat(subItem.fullPath()).isEqualTo("items[200].subItems[101]");
            assertThat(subItem.relativePath()).isEqualTo("[101]");
            assertThat(subItem.collectionFieldName()).isEqualTo("subItems");
        }
    }

    @Nested
    @DisplayName("出现序与标识渲染（边界与相邻值）")
    class OccurrenceAndIdentity {

        @Test
        @DisplayName("省略出现序与显式 NO_OCCURRENCE 等价，均不加后缀")
        void omittedOccurrence_shouldEqualNoOccurrenceMarker() {
            final ChangeLocation items = ChangeLocation.field(ChangeLocation.root(), "items");
            final ChangeLocation omitted = ChangeLocation.collectionItem(items, "A");
            final ChangeLocation explicit = ChangeLocation.collectionItem(items, "A", ChangeLocation.NO_OCCURRENCE);

            assertThat(omitted.fullPath()).isEqualTo("items[A]");
            assertThat(explicit.fullPath()).isEqualTo("items[A]");
            assertThat(explicit).isEqualTo(omitted);
        }

        @Test
        @DisplayName("相邻出现序 1 与 2 分别渲染 #1 与 #2，且两者互不相等")
        void neighbouringOccurrences_shouldRenderDistinctSuffixes() {
            final ChangeLocation items = ChangeLocation.field(ChangeLocation.root(), "items");
            final ChangeLocation first = ChangeLocation.collectionItem(items, "A", 1);
            final ChangeLocation second = ChangeLocation.collectionItem(items, "A", 2);

            assertThat(first.fullPath()).isEqualTo("items[A#1]");
            assertThat(second.fullPath()).isEqualTo("items[A#2]");
            assertThat(first).isNotEqualTo(second);
        }

        @Test
        @DisplayName("极大出现序按下标后缀渲染，不丢失后缀")
        void largeOccurrence_shouldStillRenderTheSuffix() {
            final ChangeLocation location = ChangeLocation.collectionItem(
                    ChangeLocation.field(ChangeLocation.root(), "items"), "A", Integer.MAX_VALUE);

            assertThat(location.fullPath()).isEqualTo("items[A#" + Integer.MAX_VALUE + "]");
        }

        @Test
        @DisplayName("null 标识渲染为 null 文本，不抛异常")
        void nullIdentity_shouldRenderNullText() {
            final ChangeLocation location = ChangeLocation.collectionItem(
                    ChangeLocation.field(ChangeLocation.root(), "map"), null);

            assertThat(location.fullPath()).isEqualTo("map[null]");
            assertThat(location.relativePath()).isEqualTo("[null]");
        }

        @Test
        @DisplayName("非字符串标识按 valueOf 文本渲染（Long、String 与自定义对象）")
        void nonStringIdentity_shouldRenderByValueOf() {
            final ChangeLocation items = ChangeLocation.field(ChangeLocation.root(), "items");

            assertThat(ChangeLocation.collectionItem(items, 7L).fullPath()).isEqualTo("items[7]");
            assertThat(ChangeLocation.collectionItem(items, "SKU-1").fullPath()).isEqualTo("items[SKU-1]");
            assertThat(ChangeLocation.collectionItem(items, new PositionalMarker(3)).fullPath()).isEqualTo("items[pos:3]");
        }

        @Test
        @DisplayName("深链路径按包含关系逐段拼接，长度随深度线性增长")
        void deepChain_shouldComposeEverySegment() {
            ChangeLocation current = ChangeLocation.root();
            final StringBuilder expected = new StringBuilder();
            for (int depth = 0; depth < 32; depth++) {
                current = ChangeLocation.field(current, "f" + depth);
                expected.append(depth == 0 ? "" : ".").append("f").append(depth);
            }

            assertThat(current.fullPath()).isEqualTo(expected.toString());
            assertThat(current.relativePath()).isEqualTo("f31");
        }
    }

    @Nested
    @DisplayName("失败路径")
    class FailurePaths {

        @Test
        @DisplayName("字段定位拒绝 null 包含位置")
        void field_withNullParent_shouldBeRejected() {
            assertThatThrownBy(() -> ChangeLocation.field(null, "status"))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("字段定位拒绝 null 字段名")
        void field_withNullName_shouldBeRejected() {
            assertThatThrownBy(() -> ChangeLocation.field(ChangeLocation.root(), null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("字段定位拒绝空白字段名")
        void field_withBlankName_shouldBeRejected() {
            assertThatThrownBy(() -> ChangeLocation.field(ChangeLocation.root(), " "))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("集合项定位拒绝 null 包含位置")
        void item_withNullParent_shouldBeRejected() {
            assertThatThrownBy(() -> ChangeLocation.collectionItem(null, 100))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("集合项可作为包含位置：集合项自身是集合时，嵌套集合项按既有路径规则拼接")
        void item_withCollectionItemParent_shouldComposeNestedItemPath() {
            final ChangeLocation item = ChangeLocation.collectionItem(ChangeLocation.field(ChangeLocation.root(), "items"), 1);

            final ChangeLocation nested = ChangeLocation.collectionItem(item, 2);

            assertThat(nested.fullPath()).isEqualTo("items[1][2]");
            assertThat(nested.relativePath()).isEqualTo("[2]");
            assertThat(nested.fieldName()).isNull();
            assertThat(nested.collectionFieldName()).isNull();
            assertThat(nested.isParentCollection()).isTrue();
        }

        @Test
        @DisplayName("集合项定位拒绝负出现序")
        void item_withNegativeOccurrence_shouldBeRejected() {
            final ChangeLocation items = ChangeLocation.field(ChangeLocation.root(), "items");

            assertThatThrownBy(() -> ChangeLocation.collectionItem(items, "A", -1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("值语义与对象边界")
    class ValueSemanticsAndObjectBoundary {

        @Test
        @DisplayName("定位按全部事实比较值语义：等价定位相等且散列一致，不同定位不相等")
        void location_shouldCompareAllFacts() {
            final ChangeLocation first = ChangeLocation.field(ChangeLocation.root(), "items");
            final ChangeLocation second = ChangeLocation.field(ChangeLocation.root(), "items");
            final ChangeLocation other = ChangeLocation.field(ChangeLocation.root(), "lines");

            assertThat(first).isEqualTo(second);
            assertThat(first.hashCode()).isEqualTo(second.hashCode());
            assertThat(first).isNotEqualTo(other);
            assertThat(first).isNotEqualTo(null);
            assertThat(first).isNotEqualTo("items");
        }

        @Test
        @DisplayName("同一位置的不同解释不发生冲突：完整路径相同的定位等价")
        void samePosition_shouldNotDependOnHowItWasBuilt() {
            final ChangeLocation fromFieldChain = ChangeLocation.collectionItem(
                    ChangeLocation.field(ChangeLocation.root(), "items"), 200);
            final ChangeLocation rebuilt = ChangeLocation.collectionItem(
                    ChangeLocation.field(ChangeLocation.root(), "items"), 200);

            assertThat(fromFieldChain.fullPath()).isEqualTo(rebuilt.fullPath());
            assertThat(fromFieldChain).isEqualTo(rebuilt);
        }

        @Test
        @DisplayName("toString 以完整路径表达定位")
        void toString_shouldRenderTheFullPath() {
            final ChangeLocation location = ChangeLocation.field(
                    ChangeLocation.collectionItem(ChangeLocation.field(ChangeLocation.root(), "items"), 200), "name");

            assertThat(location).hasToString("items[200].name");
        }

        @Test
        @DisplayName("定位对象不持有业务对象、结果节点或比较会话状态：实例字段只承载路径事实")
        void location_shouldNotRetainBusinessObjectsOrResultNodes() {
            final List<Class<?>> allowedFieldTypes = List.of(String.class, boolean.class);

            final List<Field> unexpectedFields = new ArrayList<>();
            for (final Field declared : ChangeLocation.class.getDeclaredFields()) {
                if (Modifier.isStatic(declared.getModifiers())) {
                    continue;
                }
                if (!allowedFieldTypes.contains(declared.getType())) {
                    unexpectedFields.add(declared);
                }
            }

            assertThat(unexpectedFields).isEmpty();
        }

        @Test
        @DisplayName("定位对象为 final 类且不暴露全参构造入口（只能经语义工厂建立）")
        void location_shouldExposeOnlySemanticFactories() {
            assertThat(ChangeLocation.class.getDeclaredConstructors()).allSatisfy(constructor ->
                    assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue());
        }
    }

    /**
     * 以 {@code pos:n} 文本表示位置标识的测试标记。
     *
     * @param position 位置
     */
    private record PositionalMarker(int position) {

        /**
         * {@inheritDoc}
         */
        @Override
        public String toString() {
            return "pos:" + this.position;
        }
    }
}
