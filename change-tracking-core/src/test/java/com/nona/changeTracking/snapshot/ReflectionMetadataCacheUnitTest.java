package com.nona.changeTracking.snapshot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InaccessibleObjectException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ReflectionMetadataCache} 与 {@link ReflectionTypeMetadata} 的场景测试：按类复用字段元数据、
 * 首次访问时准备字段访问并复用、隐藏字段与静态字段的既有语义、并发首次访问，以及访问准备失败不
 * 被记为成功。
 * <p>
 * 共享缓存是进程内单例，因此每个用例使用自己声明的探针类型作为前置状态，不依赖其他用例是否已
 * 访问过同一类型。
 */
@DisplayName("ReflectionMetadataCache 共享反射元数据缓存单元测试")
class ReflectionMetadataCacheUnitTest {

    /**
     * 元数据收集的探针：继承链的两层字段。
     */
    static class MetadataBase {

        String baseField = "base";
    }

    /**
     * 元数据收集的探针：子类声明字段、静态字段与父类字段。
     */
    static class MetadataChild extends MetadataBase {

        String childField = "child";

        String secondChildField = "second-child";

        static String staticField = "static";
    }

    /**
     * 字段隐藏探针的父类。
     */
    static class ShadowBase {

        String name = "parent";
    }

    /**
     * 字段隐藏探针：子类与父类声明同名字段。
     */
    static class ShadowChild extends ShadowBase {

        String name = "child";
    }

    /**
     * 字段隐藏探针的孙类：三级继承下的同名字段。
     */
    static class ShadowGrandChild extends ShadowChild {

        String name = "grand-child";
    }

    /**
     * 无字段探针。
     */
    static class FieldlessType {
    }

    /**
     * 字段访问复用探针。
     */
    static class FieldAccessProbe {

        String text = "first";
    }

    /**
     * 并发首次访问探针。
     */
    static class ConcurrentProbe {

        String text = "concurrent";
    }

    /**
     * 混合访问准备探针：自身字段可准备，继承自 {@link java.util.AbstractList} 的字段位于
     * {@code java.base} 未开放包，无法准备访问。
     */
    static class MixedAccessList extends java.util.AbstractList<String> {

        String ownField = "own";

        /** {@inheritDoc} */
        @Override
        public String get(final int index) {
            return null;
        }

        /** {@inheritDoc} */
        @Override
        public int size() {
            return 0;
        }
    }

    @Nested
    @DisplayName("元数据收集与缓存复用")
    class MetadataCollection {

        @Test
        @DisplayName("同一类型的两次查找应返回同一元数据实例（字段只收集一次）")
        void sameType_shouldReuseOneMetadataInstance() {
            final ReflectionTypeMetadata first = ReflectionMetadataCache.SHARED.get(MetadataChild.class);
            final ReflectionTypeMetadata second = ReflectionMetadataCache.SHARED.get(MetadataChild.class);

            assertThat(second).isSameAs(first);
        }

        @Test
        @DisplayName("不同类型应有各自的元数据实例")
        void differentTypes_shouldHaveDistinctMetadata() {
            final ReflectionTypeMetadata child = ReflectionMetadataCache.SHARED.get(MetadataChild.class);
            final ReflectionTypeMetadata base = ReflectionMetadataCache.SHARED.get(MetadataBase.class);

            assertThat(child).isNotSameAs(base);
        }

        @Test
        @DisplayName("字段顺序应为子类到父类、类内声明序")
        void fields_shouldFollowSubclassToSuperclassAndDeclarationOrder() {
            final ReflectionTypeMetadata metadata = ReflectionMetadataCache.SHARED.get(MetadataChild.class);

            assertThat(fieldNames(metadata)).containsExactly("childField", "secondChildField", "baseField");
        }

        @Test
        @DisplayName("静态字段应被收集阶段排除")
        void staticFields_shouldBeExcluded() {
            final ReflectionTypeMetadata metadata = ReflectionMetadataCache.SHARED.get(MetadataChild.class);

            assertThat(fieldNames(metadata)).doesNotContain("staticField");
        }

        @Test
        @DisplayName("字段隐藏：子类与父类的同名字段都保留且保持遍历顺序")
        void shadowedFields_shouldBothRemainInTraversalOrder() {
            final ReflectionTypeMetadata metadata = ReflectionMetadataCache.SHARED.get(ShadowChild.class);

            assertThat(fieldNames(metadata)).containsExactly("name", "name");
        }

        @Test
        @DisplayName("多级继承下的字段隐藏应保留每一层的同名字段")
        void shadowedFieldsInThreeLevels_shouldAllRemain() {
            final ReflectionTypeMetadata metadata = ReflectionMetadataCache.SHARED.get(ShadowGrandChild.class);

            assertThat(fieldNames(metadata)).containsExactly("name", "name", "name");
        }

        @Test
        @DisplayName("无字段类型应报告零字段并拒绝任何位置访问")
        void fieldlessType_shouldReportZeroSizeAndRejectAccess() {
            final ReflectionTypeMetadata metadata = ReflectionMetadataCache.SHARED.get(FieldlessType.class);

            assertThat(metadata.size()).isZero();
            assertThatThrownBy(() -> metadata.access(0)).isInstanceOf(IndexOutOfBoundsException.class);
        }

        @Test
        @DisplayName("null 类型应被拒绝")
        void nullType_shouldBeRejected() {
            assertThatThrownBy(() -> ReflectionMetadataCache.SHARED.get(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("字段访问准备")
    class FieldAccessPreparation {

        @Test
        @DisplayName("同一位置的重复访问应复用同一访问对象，且读取当前对象的值")
        void access_shouldPrepareOnceAndReadTheCurrentInstance() {
            final ReflectionTypeMetadata metadata = ReflectionMetadataCache.SHARED.get(FieldAccessProbe.class);
            final FieldAccessProbe first = new FieldAccessProbe();
            final FieldAccessProbe second = new FieldAccessProbe();
            first.text = "first";
            second.text = "second";

            final ReflectionTypeMetadata.ReflectionFieldAccess access = metadata.access(0);

            assertThat(metadata.access(0)).isSameAs(access);
            assertThat(access.fieldName()).isEqualTo("text");
            assertThat(read(access, first)).isEqualTo("first");
            assertThat(read(access, second)).isEqualTo("second");
        }

        @Test
        @DisplayName("不同位置的访问对象应互相独立")
        void access_differentIndexes_shouldReturnDistinctInstances() {
            final ReflectionTypeMetadata metadata = ReflectionMetadataCache.SHARED.get(MetadataChild.class);

            assertThat(metadata.access(0)).isNotSameAs(metadata.access(2));
            assertThat(metadata.access(0).fieldName()).isEqualTo("childField");
            assertThat(metadata.access(2).fieldName()).isEqualTo("baseField");
        }

        @Test
        @DisplayName("越界位置访问应抛 IndexOutOfBoundsException")
        void access_outOfRange_shouldBeRejected() {
            final ReflectionTypeMetadata metadata = ReflectionMetadataCache.SHARED.get(FieldAccessProbe.class);

            assertThatThrownBy(() -> metadata.access(1)).isInstanceOf(IndexOutOfBoundsException.class);
            assertThatThrownBy(() -> metadata.access(-1)).isInstanceOf(IndexOutOfBoundsException.class);
        }
    }

    @Nested
    @DisplayName("并发首次访问")
    class ConcurrentFirstAccess {

        @Test
        @DisplayName("并发首次访问应发布同一个元数据与同一个访问对象")
        void concurrentFirstAccess_shouldPublishOneInstanceEach() throws Exception {
            final AtomicReference<ReflectionTypeMetadata> firstMetadata = new AtomicReference<>();
            final AtomicReference<ReflectionTypeMetadata> secondMetadata = new AtomicReference<>();
            final AtomicReference<ReflectionTypeMetadata.ReflectionFieldAccess> firstAccess = new AtomicReference<>();
            final AtomicReference<ReflectionTypeMetadata.ReflectionFieldAccess> secondAccess = new AtomicReference<>();
            final CountDownLatch start = new CountDownLatch(1);
            final CountDownLatch done = new CountDownLatch(2);

            final Thread first = new Thread(() -> {
                await(start);
                try {
                    final ReflectionTypeMetadata metadata = ReflectionMetadataCache.SHARED.get(ConcurrentProbe.class);
                    firstMetadata.set(metadata);
                    firstAccess.set(metadata.access(0));
                } finally {
                    done.countDown();
                }
            });
            final Thread second = new Thread(() -> {
                await(start);
                try {
                    final ReflectionTypeMetadata metadata = ReflectionMetadataCache.SHARED.get(ConcurrentProbe.class);
                    secondMetadata.set(metadata);
                    secondAccess.set(metadata.access(0));
                } finally {
                    done.countDown();
                }
            });

            first.start();
            second.start();
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();

            assertThat(firstMetadata.get()).isNotNull();
            assertThat(secondMetadata.get()).isNotNull();
            assertThat(firstAccess.get()).isNotNull();
            assertThat(secondMetadata.get()).isSameAs(firstMetadata.get());
            assertThat(secondAccess.get()).isSameAs(firstAccess.get());
        }
    }

    @Nested
    @DisplayName("访问准备失败")
    class AccessPreparationFailure {

        @Test
        @DisplayName("不可准备字段应在访问处失败，且失败不被记为成功")
        void inaccessibleField_shouldFailOnEveryAccessAttempt() {
            final ReflectionTypeMetadata metadata = ReflectionMetadataCache.SHARED.get(MixedAccessList.class);

            assertThat(metadata.size()).isEqualTo(2);
            assertThat(metadata.access(0).fieldName()).isEqualTo("ownField");
            assertThatThrownBy(() -> metadata.access(1)).isInstanceOf(InaccessibleObjectException.class);
            assertThatThrownBy(() -> metadata.access(1)).isInstanceOf(InaccessibleObjectException.class);
        }

        @Test
        @DisplayName("访问准备失败不应污染该类型的其他字段与缓存条目")
        void preparationFailure_shouldNotPoisonOtherEntries() {
            final ReflectionTypeMetadata firstLookup = ReflectionMetadataCache.SHARED.get(MixedAccessList.class);
            assertThatThrownBy(() -> firstLookup.access(1)).isInstanceOf(InaccessibleObjectException.class);

            final ReflectionTypeMetadata secondLookup = ReflectionMetadataCache.SHARED.get(MixedAccessList.class);

            assertThat(secondLookup).isSameAs(firstLookup);
            assertThat(secondLookup.access(0).fieldName()).isEqualTo("ownField");
            assertThat(ReflectionMetadataCache.SHARED.get(FieldAccessProbe.class).access(0).fieldName())
                    .isEqualTo("text");
        }
    }

    /**
     * 收集元数据的字段名序列。
     *
     * @param metadata 目标元数据
     * @return 按遍历顺序排列的字段名
     */
    private static List<String> fieldNames(final ReflectionTypeMetadata metadata) {
        final List<String> names = new ArrayList<>();
        for (int index = 0; index < metadata.size(); index++) {
            names.add(metadata.access(index).fieldName());
        }
        return names;
    }

    /**
     * 通过已准备的访问对象读取字段值。
     *
     * @param access 已准备的字段访问
     * @param target 目标对象
     * @return 字段当前值
     */
    private static Object read(final ReflectionTypeMetadata.ReflectionFieldAccess access, final Object target) {
        try {
            return access.read(target);
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException("Unexpected read failure", e);
        }
    }

    /**
     * 等待并发用例的起跑信号。
     *
     * @param start 起跑信号
     */
    private static void await(final CountDownLatch start) {
        try {
            start.await(30, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the start signal", e);
        }
    }
}