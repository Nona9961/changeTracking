package com.nona.changeTracking.internal.snapshot;

import com.nona.changeTracking.domain.capability.TrackingCapability;
import com.nona.changeTracking.domain.model.changeset.Change;
import com.nona.changeTracking.domain.model.changeset.ChangeSet;
import com.nona.changeTracking.domain.model.changeset.ItemAddedChange;
import com.nona.changeTracking.domain.model.changeset.ItemRemovedChange;
import com.nona.changeTracking.domain.model.changeset.ValueChange;
import com.nona.changeTracking.domain.model.snapshot.ObjectNode;
import com.nona.changeTracking.domain.model.snapshot.PrimitiveNode;
import com.nona.changeTracking.domain.model.snapshot.ValueNode;
import com.nona.changeTracking.domain.model.tracking.BaselineSnapshot;
import com.nona.changeTracking.domain.model.tracking.ChangeTracker;
import com.nona.changeTracking.internal.capability.DefaultTrackingCapabilityProvider;
import com.nona.changeTracking.spi.TrackingCapabilityProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InaccessibleObjectException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 快照缓存接入的装配面集成测试：用真实 SPI 装配的默认 provider、真实 capability 与真实
 * {@link ChangeTracker} 链路验证单元测试用替身覆盖不到的面。
 * <p>
 * 覆盖：ServiceLoader 发现默认 provider 后的完整计算链路、基线捕获与恢复互操作、两个不同配置的
 * capability 交替使用时的结果隔离（值类型分类与标识提取各自独立）、类级元数据跨 capability 复用，
 * 以及提取器失败与字段访问失败在真实链路上的传播。
 */
@DisplayName("MetadataCache 装配面集成测试")
class MetadataCacheAssemblyIntegrationTest {

    /**
     * 集成测试的订单聚合根探针。
     */
    static class Order {

        Long id = 1L;

        String orderNumber = "SO-1";

        String status = "CREATED";

        List<LineItem> items = new ArrayList<>();
    }

    /**
     * 集成测试的集合项探针。
     */
    static class LineItem {

        Long id;

        String sku;

        int quantity;

        /**
         * 创建集合项。
         *
         * @param id       业务标识
         * @param sku      商品编码
         * @param quantity 数量
         */
        LineItem(final Long id, final String sku, final int quantity) {
            this.id = id;
            this.sku = sku;
            this.quantity = quantity;
        }
    }

    /**
     * 集成测试的不可变值类型探针。
     */
    static final class Money {

        private final BigDecimal amount;

        /**
         * 创建金额值类型。
         *
         * @param amount 金额
         */
        Money(final BigDecimal amount) {
            this.amount = amount;
        }
    }

    /**
     * 携带值类型字段的探针。
     */
    static class PricedOrder {

        String orderNumber = "SO-2";

        Money price = new Money(new BigDecimal("19.99"));
    }

    @Nested
    @DisplayName("真实 SPI 装配与完整链路")
    class RealAssembly {

        @Test
        @DisplayName("ServiceLoader 应发现默认 provider 并驱动完整计算链路")
        void serviceLoaderDiscoveredProvider_shouldDriveTheWholeChain() {
            final TrackingCapabilityProvider provider = defaultProvider();
            final ChangeTracker tracker = new ChangeTracker(provider.create());
            final Order order = trackedOrder();

            tracker.track(order);
            order.status = "PAID";

            final ChangeSet changeSet = tracker.calculateChanges();

            assertThat(changeSet.isEmpty()).isFalse();
            assertThat(changeSet.getLeafChanges()).hasSize(1);
            final ValueChange valueChange = (ValueChange) changeSet.getLeafChanges().get(0);
            assertThat(valueChange.fullPath()).isEqualTo("status");
            assertThat(valueChange.oldValue()).isEqualTo("CREATED");
            assertThat(valueChange.newValue()).isEqualTo("PAID");
        }

        @Test
        @DisplayName("完整视图与叶子视图应同时反映缓存接入后的变更")
        void bothViews_shouldReflectTheSameChanges() {
            final ChangeTracker tracker = new ChangeTracker(defaultProvider().create());
            final Order order = trackedOrder();

            tracker.track(order);
            order.status = "PAID";

            final ChangeSet changeSet = tracker.calculateChanges();

            assertThat(changeSet.getAllChanges()).isNotEmpty();
            assertThat(changeSet.getLeafChanges()).hasSize(1);
            assertThat(changeSet.getLeafChanges().get(0).fullPath()).isEqualTo("status");
        }

        @Test
        @DisplayName("缓存接入后基线捕获与恢复应互操作：恢复基线仍检测到同一变更")
        void baselineRoundTrip_shouldKeepDetectingTheSameChange() {
            final TrackingCapability<?> capability = defaultProvider().create();
            final ChangeTracker source = new ChangeTracker(capability);
            final Order order = trackedOrder();
            source.track(order);
            final BaselineSnapshot baseline = source.captureBaseline();

            order.status = "PAID";

            final ChangeTracker restored = ChangeTracker.fromBaseline(capability, baseline);
            final ChangeSet changeSet = restored.calculateChanges();

            assertThat(changeSet.getLeafChanges()).hasSize(1);
            final ValueChange valueChange = (ValueChange) changeSet.getLeafChanges().get(0);
            assertThat(valueChange.fullPath()).isEqualTo("status");
            assertThat(valueChange.newValue()).isEqualTo("PAID");
        }

        @Test
        @DisplayName("完整链路上同一类的类级元数据应跨 capability 复用同一实例")
        void classMetadata_shouldBeSharedAcrossCapabilities() {
            final TrackingCapability<?> first = defaultProvider().create();
            final TrackingCapability<?> second = defaultProvider().create();
            final ChangeTracker firstTracker = new ChangeTracker(first);
            final ChangeTracker secondTracker = new ChangeTracker(second);
            final Order firstOrder = trackedOrder();
            final Order secondOrder = trackedOrder();

            firstTracker.track(firstOrder);
            final ReflectionTypeMetadata metadataAfterFirst = ReflectionMetadataCache.SHARED.get(Order.class);
            secondTracker.track(secondOrder);

            assertThat(ReflectionMetadataCache.SHARED.get(Order.class)).isSameAs(metadataAfterFirst);
            assertThat(metadataAfterFirst.access(0).fieldName()).isEqualTo("id");
            firstTracker.calculateChanges();
            secondTracker.calculateChanges();
        }
    }

    @Nested
    @DisplayName("配置隔离")
    class ConfigurationIsolation {

        @Test
        @DisplayName("标识提取配置不同：同一次项替换一个按业务标识匹配、一个按身份匹配")
        void differingExtractorConfiguration_shouldKeepResultsIndependent() {
            final TrackingCapabilityProvider identifying = configuredProvider();
            identifying.withIdentifier(LineItem.class, item -> item.id);
            final TrackingCapabilityProvider plain = new DefaultTrackingCapabilityProvider();

            final Order identifiedOrder = trackedOrder();
            final Order plainOrder = trackedOrder();
            final ChangeTracker identifiedTracker = new ChangeTracker(identifying.create());
            final ChangeTracker plainTracker = new ChangeTracker(plain.create());
            identifiedTracker.track(identifiedOrder);
            plainTracker.track(plainOrder);

            identifiedOrder.items.set(1, new LineItem(2L, "SKU-2", 7));
            plainOrder.items.set(1, new LineItem(2L, "SKU-2", 7));

            final List<Change> identifiedChanges = identifiedTracker.calculateChanges().getLeafChanges();
            final List<Change> plainChanges = plainTracker.calculateChanges().getLeafChanges();

            assertThat(identifiedChanges).hasSize(1);
            assertThat(identifiedChanges.get(0)).isInstanceOf(ValueChange.class);
            assertThat(((ValueChange) identifiedChanges.get(0)).fullPath()).isEqualTo("items[2].quantity");
            assertThat(plainChanges).anyMatch(change -> change instanceof ItemAddedChange);
            assertThat(plainChanges).anyMatch(change -> change instanceof ItemRemovedChange);
        }

        @Test
        @DisplayName("值类型配置不同：注册方快照为 PrimitiveNode，未注册方递归展开为 ObjectNode")
        void differingValueTypeConfiguration_shouldKeepClassificationIndependent() {
            final TrackingCapabilityProvider registering = configuredProvider();
            registering.withValueType(Money.class);
            final TrackingCapabilityProvider plain = new DefaultTrackingCapabilityProvider();
            final PricedOrder registeringOrder = new PricedOrder();
            final PricedOrder plainOrder = new PricedOrder();
            final ChangeTracker registeringTracker = new ChangeTracker(registering.create());
            final ChangeTracker plainTracker = new ChangeTracker(plain.create());

            registeringTracker.track(registeringOrder);
            plainTracker.track(plainOrder);

            assertThat(fieldOf(registeringTracker.captureBaseline(), registeringOrder, "price"))
                    .isInstanceOf(PrimitiveNode.class);
            assertThat(fieldOf(plainTracker.captureBaseline(), plainOrder, "price"))
                    .isInstanceOf(ObjectNode.class);
        }

        @Test
        @DisplayName("两个配置交替使用：后使用的配置不继承先使用配置的规则")
        void alternatingCapabilities_shouldNotInheritRules() {
            final TrackingCapabilityProvider registering = configuredProvider();
            registering.withValueType(Money.class);
            final TrackingCapabilityProvider plain = new DefaultTrackingCapabilityProvider();
            final ChangeTracker registeringTracker = new ChangeTracker(registering.create());
            final ChangeTracker plainTracker = new ChangeTracker(plain.create());
            final PricedOrder registeringOrder = new PricedOrder();
            final PricedOrder plainOrder = new PricedOrder();

            registeringTracker.track(registeringOrder);
            plainTracker.track(plainOrder);
            registeringTracker.calculateChanges();

            final PrimitiveNode registeringPrice =
                    (PrimitiveNode) fieldOf(registeringTracker.captureBaseline(), registeringOrder, "price");
            final ValueNode plainPrice = fieldOf(plainTracker.captureBaseline(), plainOrder, "price");

            assertThat(registeringPrice.value()).isSameAs(registeringOrder.price);
            assertThat(plainPrice).isInstanceOf(ObjectNode.class);
            assertThat(((ObjectNode) plainPrice).field("amount")).isInstanceOf(PrimitiveNode.class);
        }
    }

    @Nested
    @DisplayName("失败传播")
    class FailurePropagation {

        @Test
        @DisplayName("提取器抛出的异常应经真实链路原样传播")
        void extractorFailure_shouldPropagateThroughTheChain() {
            final IllegalStateException failure = new IllegalStateException("identifier unavailable");
            final TrackingCapabilityProvider provider = configuredProvider();
            provider.withIdentifier(LineItem.class, item -> {
                throw failure;
            });
            final ChangeTracker tracker = new ChangeTracker(provider.create());
            final Order order = trackedOrder();

            assertThatThrownBy(() -> tracker.track(order)).isSameAs(failure);
        }

        @Test
        @DisplayName("字段访问不可准备时应在真实链路上抛出 InaccessibleObjectException")
        void inaccessibleField_shouldFailOnTheWholeChain() {
            final ChangeTracker tracker = new ChangeTracker(defaultProvider().create());

            assertThatThrownBy(() -> tracker.track(new java.util.Random(7)))
                    .isInstanceOf(InaccessibleObjectException.class);
        }

        @Test
        @DisplayName("null 能力应被拒绝")
        void nullCapability_shouldBeRejected() {
            assertThatThrownBy(() -> new ChangeTracker(null)).isInstanceOf(NullPointerException.class);
        }
    }

    /**
     * 通过 ServiceLoader 获取默认 provider。
     *
     * @return 默认 provider
     */
    private static TrackingCapabilityProvider defaultProvider() {
        for (final TrackingCapabilityProvider provider : ServiceLoader.load(TrackingCapabilityProvider.class)) {
            if (DefaultTrackingCapabilityProvider.class.isInstance(provider)) {
                return provider;
            }
        }
        throw new IllegalStateException("Default provider not discovered through ServiceLoader");
    }

    /**
     * 创建未配置的默认 provider 实例（不依赖 ServiceLoader）。
     *
     * @return 默认 provider 实例
     */
    private static TrackingCapabilityProvider configuredProvider() {
        return new DefaultTrackingCapabilityProvider();
    }

    /**
     * 构造带两个集合项的订单探针，每个用例自行构造前置状态。
     *
     * @return 全新的订单探针
     */
    private static Order trackedOrder() {
        final Order order = new Order();
        order.items.add(new LineItem(1L, "SKU-1", 1));
        order.items.add(new LineItem(2L, "SKU-2", 2));
        return order;
    }

    /**
     * 读取基线快照中某对象的指定字段节点。
     *
     * @param baseline 基线快照
     * @param entity   基线中的实体
     * @param field    字段名
     * @return 该字段的节点
     */
    private static ValueNode fieldOf(final BaselineSnapshot baseline, final Object entity, final String field) {
        final ValueNode root = baseline.entities().get(entity);
        assertThat(root).as("baseline entry for the entity").isInstanceOf(ObjectNode.class);
        return ((ObjectNode) root).field(field);
    }
}