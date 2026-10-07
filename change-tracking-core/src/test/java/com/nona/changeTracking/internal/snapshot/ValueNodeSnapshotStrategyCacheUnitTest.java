package com.nona.changeTracking.internal.snapshot;

import com.nona.changeTracking.domain.capability.TrackingConfiguration;
import com.nona.changeTracking.domain.model.snapshot.ArrayNode;
import com.nona.changeTracking.domain.model.snapshot.CollectionNode;
import com.nona.changeTracking.domain.model.snapshot.NullNode;
import com.nona.changeTracking.domain.model.snapshot.ObjectNode;
import com.nona.changeTracking.domain.model.snapshot.PrimitiveNode;
import com.nona.changeTracking.domain.model.snapshot.ValueNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InaccessibleObjectException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ValueNodeSnapshotStrategy} 接入反射与配置规则缓存后的场景测试：复用不改变既有字段读取、
 * 标识提取与失败行为，配置交替时分类与提取规则互不污染，且字段访问准备经共享元数据缓存完成。
 * <p>
 * 每个用例自行构造策略与业务对象作为前置状态；共享缓存是进程内单例，因此断言不依赖缓存为空。
 */
@DisplayName("ValueNodeSnapshotStrategy 缓存接入单元测试")
class ValueNodeSnapshotStrategyCacheUnitTest {

    /**
     * 隐藏字段探针的父类。
     */
    static class ShadowBase {

        String name = "parent";

        String baseOnly = "base-only";
    }

    /**
     * 隐藏字段探针：子类与父类同名字段，并带一级继承。
     */
    static class ShadowChild extends ShadowBase {

        String name = "child";

        String childOnly = "child-only";
    }

    /**
     * 隐藏字段探针的孙类。
     */
    static class ShadowGrandChild extends ShadowChild {

        String name = "grand-child";
    }

    /**
     * 静态字段与 transient 字段探针。
     */
    static class FieldKindProbe {

        static String staticField = "static";

        transient String transientField = "transient";

        String instanceField = "instance";
    }

    /**
     * 值类型注册探针：不可变金额。
     */
    static final class Money {

        private final BigDecimal amount;

        /**
         * 创建金额探针。
         *
         * @param amount 金额
         */
        Money(final BigDecimal amount) {
            this.amount = amount;
        }
    }

    /**
     * 携带金额字段的订单探针。
     */
    static class PricedOrder {

        String orderNumber = "SO-1";

        Money price = new Money(new BigDecimal("9.99"));
    }

    /**
     * 标识接口探针。
     */
    interface Identified {

        /**
         * 返回业务标识。
         *
         * @return 业务标识
         */
        Long id();
    }

    /**
     * 实现标识接口但未在自身声明接口的子类探针。
     */
    static class IdentifiedItem implements Identified {

        /** {@inheritDoc} */
        @Override
        public Long id() {
            return 42L;
        }
    }

    /**
     * 无标识配置的集合项探针。
     */
    static class PlainItem {

        String sku = "SKU-1";
    }

    @Nested
    @DisplayName("缓存复用后的既有行为")
    class ExistingBehaviour {

        @Test
        @DisplayName("同一类型的多个实例应各自读取当前字段值，不复用首次读取结果")
        void sameTypeInstances_shouldReadTheirOwnFieldValues() {
            final ValueNodeSnapshotStrategy strategy = strategyOf(Map.of(), Set.of(), Set.of());
            final ShadowChild first = new ShadowChild();
            final ShadowChild second = new ShadowChild();
            first.baseOnly = "first-base";
            second.baseOnly = "second-base";

            final ObjectNode firstNode = (ObjectNode) strategy.createSnapshot(first).getSnapshotData();
            final ObjectNode secondNode = (ObjectNode) strategy.createSnapshot(second).getSnapshotData();

            assertThat(firstNode.field("baseOnly")).isEqualTo(new PrimitiveNode("first-base"));
            assertThat(secondNode.field("baseOnly")).isEqualTo(new PrimitiveNode("second-base"));
        }

        @Test
        @DisplayName("字段隐藏应保留子类字段值，多级继承逐层保留遍历顺序")
        void shadowedFields_shouldKeepTheSubclassValue() {
            final ValueNodeSnapshotStrategy strategy = strategyOf(Map.of(), Set.of(), Set.of());

            final ObjectNode childNode = (ObjectNode) strategy.createSnapshot(new ShadowChild()).getSnapshotData();
            final ObjectNode grandChildNode =
                    (ObjectNode) strategy.createSnapshot(new ShadowGrandChild()).getSnapshotData();

            assertThat(childNode.field("name")).isEqualTo(new PrimitiveNode("child"));
            assertThat(grandChildNode.field("name")).isEqualTo(new PrimitiveNode("grand-child"));
        }

        @Test
        @DisplayName("字段输出顺序应保持声明序（子类到父类）")
        void fieldOrder_shouldFollowDeclarationOrder() {
            final ValueNodeSnapshotStrategy strategy = strategyOf(Map.of(), Set.of(), Set.of());

            final ObjectNode node = (ObjectNode) strategy.createSnapshot(new ShadowChild()).getSnapshotData();

            assertThat(fieldNames(node)).containsExactly("name", "childOnly", "baseOnly");
        }

        @Test
        @DisplayName("静态字段应排除，transient 字段应参与快照")
        void staticFields_shouldBeExcludedWhileTransientFieldsRemain() {
            final ValueNodeSnapshotStrategy strategy = strategyOf(Map.of(), Set.of(), Set.of());

            final ObjectNode node = (ObjectNode) strategy.createSnapshot(new FieldKindProbe()).getSnapshotData();

            assertThat(fieldNames(node)).containsExactly("transientField", "instanceField");
        }

        @Test
        @DisplayName("注册的自定义值类型应继续快照为 PrimitiveNode（不递归展开）")
        void registeredValueType_shouldRemainAPrimitiveNode() {
            final ValueNodeSnapshotStrategy strategy =
                    strategyOf(Map.of(), Set.of(Money.class), Set.of());
            final PricedOrder order = new PricedOrder();

            final ObjectNode node = (ObjectNode) strategy.createSnapshot(order).getSnapshotData();

            assertThat(node.field("price")).isInstanceOf(PrimitiveNode.class);
            assertThat(((PrimitiveNode) node.field("price")).value()).isSameAs(order.price);
        }

        @Test
        @DisplayName("未注册的复杂对象应递归展开为 ObjectNode")
        void unregisteredComplexType_shouldBeDehydrated() {
            final ValueNodeSnapshotStrategy strategy = strategyOf(Map.of(), Set.of(), Set.of());

            final ObjectNode node = (ObjectNode) strategy.createSnapshot(new PricedOrder()).getSnapshotData();

            assertThat(node.field("price")).isInstanceOf(ObjectNode.class);
        }

        @Test
        @DisplayName("接口注册的提取器应沿类链命中实现类并用于集合项标识")
        void extractorRegisteredOnInterface_shouldMatchImplementations() {
            final ValueNodeSnapshotStrategy strategy = strategyOf(
                    Map.of(Identified.class, target -> ((Identified) target).id()), Set.of(), Set.of());

            final CollectionNode items = (CollectionNode) strategy.createSnapshot(List.of(new IdentifiedItem()))
                    .getSnapshotData();

            assertThat(((ObjectNode) items.item(0)).identifier()).isEqualTo(42L);
        }

        @Test
        @DisplayName("未注册提取器的类型应回退 identityHashCode（非 null 且稳定）")
        void unregisteredType_shouldFallBackToIdentityHashCode() {
            final ValueNodeSnapshotStrategy strategy = strategyOf(Map.of(), Set.of(), Set.of());
            final PlainItem item = new PlainItem();

            final CollectionNode node = (CollectionNode) strategy.createSnapshot(List.of(item)).getSnapshotData();

            assertThat(((ObjectNode) node.item(0)).identifier()).isEqualTo(System.identityHashCode(item));
        }

        @Test
        @DisplayName("提取器返回 null 时应回退 identityHashCode（保持既有回退语义）")
        void extractorReturningNull_shouldFallBackToIdentityHashCode() {
            final ValueNodeSnapshotStrategy strategy = strategyOf(
                    Map.of(PlainItem.class, target -> null), Set.of(), Set.of());
            final PlainItem item = new PlainItem();

            final CollectionNode node = (CollectionNode) strategy.createSnapshot(List.of(item)).getSnapshotData();

            assertThat(((ObjectNode) node.item(0)).identifier()).isEqualTo(System.identityHashCode(item));
        }

        @Test
        @DisplayName("null 根应保持 NullNode 语义")
        void nullRoot_shouldRemainANullNode() {
            final ValueNodeSnapshotStrategy strategy = strategyOf(Map.of(), Set.of(), Set.of());

            assertThat(strategy.createSnapshot(null).getSnapshotData()).isEqualTo(new NullNode());
        }
    }

    @Nested
    @DisplayName("配置交替与数组边界")
    class ConfigurationAlternation {

        @Test
        @DisplayName("两个配置交替快照时值类型分类互不污染")
        void alternatingConfigurations_shouldKeepValueTypeClassificationIndependent() {
            final ValueNodeSnapshotStrategy withRegistration =
                    strategyOf(Map.of(), Set.of(Money.class), Set.of());
            final ValueNodeSnapshotStrategy withoutRegistration =
                    strategyOf(Map.of(), Set.of(), Set.of());
            final PricedOrder order = new PricedOrder();

            final ValueNode withRegistrationFirst = withRegistration.createSnapshot(order).getSnapshotData();
            final ValueNode withoutRegistrationFirst = withoutRegistration.createSnapshot(order).getSnapshotData();
            final ValueNode withRegistrationSecond = withRegistration.createSnapshot(order).getSnapshotData();
            final ValueNode withoutRegistrationSecond = withoutRegistration.createSnapshot(order).getSnapshotData();

            assertThat(((ObjectNode) withRegistrationFirst).field("price")).isInstanceOf(PrimitiveNode.class);
            assertThat(((ObjectNode) withRegistrationSecond).field("price")).isInstanceOf(PrimitiveNode.class);
            assertThat(((ObjectNode) withoutRegistrationFirst).field("price")).isInstanceOf(ObjectNode.class);
            assertThat(((ObjectNode) withoutRegistrationSecond).field("price")).isInstanceOf(ObjectNode.class);
        }

        @Test
        @DisplayName("两个配置交替快照时标识提取结果互不污染")
        void alternatingConfigurations_shouldKeepExtractorResultsIndependent() {
            final ValueNodeSnapshotStrategy identifying = strategyOf(
                    Map.of(IdentifiedItem.class, target -> ((IdentifiedItem) target).id()), Set.of(), Set.of());
            final ValueNodeSnapshotStrategy fallingBack = strategyOf(Map.of(), Set.of(), Set.of());
            final IdentifiedItem item = new IdentifiedItem();

            final CollectionNode identified =
                    (CollectionNode) identifying.createSnapshot(List.of(item)).getSnapshotData();
            final CollectionNode fallenBack =
                    (CollectionNode) fallingBack.createSnapshot(List.of(item)).getSnapshotData();

            assertThat(((ObjectNode) identified.item(0)).identifier()).isEqualTo(42L);
            assertThat(((ObjectNode) fallenBack.item(0)).identifier()).isEqualTo(System.identityHashCode(item));
        }

        @Test
        @DisplayName("Object.class 注册的提取器不参与查找，标识回退 identityHashCode")
        void objectClassExtractor_shouldNotBeSearched() {
            final ValueNodeSnapshotStrategy strategy = strategyOf(
                    Map.of(Object.class, target -> "object-extractor"), Set.of(), Set.of());
            final PlainItem item = new PlainItem();

            final CollectionNode node = (CollectionNode) strategy.createSnapshot(List.of(item)).getSnapshotData();

            assertThat(((ObjectNode) node.item(0)).identifier()).isEqualTo(System.identityHashCode(item));
        }

        @Test
        @DisplayName("值数组与复杂对象数组应保持既有语义（含多维）")
        void arrays_shouldKeepValueAndComplexObjectSemantics() {
            final ValueNodeSnapshotStrategy strategy = strategyOf(Map.of(), Set.of(), Set.of());

            assertThat(strategy.createSnapshot(new int[] {1, 2}).getSnapshotData()).isInstanceOf(ArrayNode.class);
            assertThat(strategy.createSnapshot(new String[] {"a"}).getSnapshotData()).isInstanceOf(ArrayNode.class);
            assertThat(strategy.createSnapshot(new int[][] {{1}}).getSnapshotData()).isInstanceOf(ArrayNode.class);
            assertThat(strategy.createSnapshot(new PlainItem[] {new PlainItem()}).getSnapshotData())
                    .isInstanceOf(CollectionNode.class);
            assertThat(strategy.createSnapshot(new PlainItem[][] {{new PlainItem()}}).getSnapshotData())
                    .isInstanceOf(CollectionNode.class);
        }

        @Test
        @DisplayName("值数组应保持防御拷贝：修改源数组不改变快照")
        void valueArraySnapshot_shouldDefendAgainstLaterMutation() {
            final ValueNodeSnapshotStrategy strategy = strategyOf(Map.of(), Set.of(), Set.of());
            final String[] source = {"first"};

            final ArrayNode node = (ArrayNode) strategy.createSnapshot(source).getSnapshotData();
            source[0] = "changed";

            assertThat(((String[]) node.array())[0]).isEqualTo("first");
        }

        @Test
        @DisplayName("重复快照同一类型应复用同一份类级元数据，且结果保持一致")
        void repeatedSnapshots_shouldReuseOneClassMetadataInstance() {
            final ValueNodeSnapshotStrategy strategy = strategyOf(Map.of(), Set.of(), Set.of());
            final ShadowChild probe = new ShadowChild();

            final ValueNode first = strategy.createSnapshot(probe).getSnapshotData();
            final ReflectionTypeMetadata metadata = ReflectionMetadataCache.SHARED.get(ShadowChild.class);
            final ValueNode second = strategy.createSnapshot(probe).getSnapshotData();

            assertThat(second).isEqualTo(first);
            assertThat(ReflectionMetadataCache.SHARED.get(ShadowChild.class)).isSameAs(metadata);
            assertThat(metadata.access(0).fieldName()).isEqualTo("name");
        }
    }

    @Nested
    @DisplayName("失败行为")
    class FailureBehaviour {

        @Test
        @DisplayName("字段访问不可准备时应在读取处抛出，且访问准备经共享元数据缓存完成")
        void inaccessibleField_shouldFailAtTheReadThroughTheSharedCache() {
            final ValueNodeSnapshotStrategy strategy = strategyOf(Map.of(), Set.of(), Set.of());

            assertThatThrownBy(() -> strategy.createSnapshot(new java.util.Random(1)))
                    .isInstanceOf(InaccessibleObjectException.class)
                    .satisfies(thrown -> assertThat(stackContainsReflectionCacheFrame(thrown)).isTrue());
        }

        @Test
        @DisplayName("失败类型不应污染共享元数据：其他类型或实例仍可正常快照")
        void failureOnOneType_shouldKeepOtherTypesUsable() {
            final ValueNodeSnapshotStrategy strategy = strategyOf(Map.of(), Set.of(), Set.of());

            assertThatThrownBy(() -> strategy.createSnapshot(new java.util.Random(2)))
                    .isInstanceOf(InaccessibleObjectException.class);

            final ObjectNode node = (ObjectNode) strategy.createSnapshot(new FieldKindProbe()).getSnapshotData();
            assertThat(node.field("instanceField")).isEqualTo(new PrimitiveNode("instance"));
        }

        @Test
        @DisplayName("提取器抛出的异常应原样传播，不吞掉也不回退")
        void extractorFailure_shouldPropagateUnchanged() {
            final IllegalStateException failure = new IllegalStateException("extractor failed");
            final ValueNodeSnapshotStrategy strategy = strategyOf(
                    Map.of(PlainItem.class, target -> {
                        throw failure;
                    }), Set.of(), Set.of());

            assertThatThrownBy(() -> strategy.createSnapshot(List.of(new PlainItem())))
                    .isSameAs(failure);
        }

        @Test
        @DisplayName("null 配置应被拒绝")
        void nullConfiguration_shouldBeRejected() {
            assertThatThrownBy(() -> new ValueNodeSnapshotStrategy(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    /**
     * 构造绑定给定配置的快照策略。
     *
     * @param extractors    标识提取器映射
     * @param valueTypes    自定义值类型集合
     * @param valuePackages 自定义值类型包集合
     * @return 快照策略
     */
    private static ValueNodeSnapshotStrategy strategyOf(final Map<Class<?>, Function<Object, Object>> extractors,
                                                        final Set<Class<?>> valueTypes,
                                                        final Set<String> valuePackages) {
        return new ValueNodeSnapshotStrategy(new TrackingConfiguration(extractors, valueTypes, valuePackages));
    }

    /**
     * 收集对象节点的字段名序列。
     *
     * @param node 对象节点
     * @return 按节点迭代顺序排列的字段名
     */
    private static List<String> fieldNames(final ObjectNode node) {
        final List<String> names = new ArrayList<>();
        node.forEachField((name, ignored) -> names.add(name));
        return names;
    }

    /**
     * 判断异常堆栈中是否出现反射元数据缓存的准备帧。
     *
     * @param thrown 捕获到的异常
     * @return 堆栈中存在 {@code internal.snapshot} 反射元数据类的帧时返回 true
     */
    private static boolean stackContainsReflectionCacheFrame(final Throwable thrown) {
        for (final StackTraceElement element : thrown.getStackTrace()) {
            if (element.getClassName().startsWith(
                    "com.nona.changeTracking.internal.snapshot.Reflection")) {
                return true;
            }
        }
        return false;
    }
}