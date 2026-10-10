package com.nona.changeTracking.snapshot;

import com.nona.changeTracking.tracking.TrackingConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ReflectionMetadataCache} 的类卸载诊断：用隔离类加载器加载探针类，快照其实例后释放全部强引用，
 * 观察探针实例与类加载器是否在有限次回收尝试内变为不可达。
 * <p>
 * 诊断口径：共享缓存继承 {@link ClassValue}，条目挂在目标 {@link Class} 上，不建立强键全局
 * {@code Map}，因此目标类及其加载器满足卸载条件时可以回收；单次 {@code System.gc()} 未回收不判为泄漏，
 * 本用例以有限次回收尝试（含分配压力）作为回收结果的观察窗口，并把实例释放与类加载器释放分开观察：
 * 缓存不保存业务实例是硬性契约（实例必须可回收），类与加载器回收同时受 JDK 类元数据缓存影响，
 * 因此使用同一个有界窗口。
 * <p>
 * 诊断本身不引入类加载器机制到被测算法：隔离加载器只出现在本用例中，生产链路仍由每次运行的 JVM
 * 类加载器承担。
 */
@DisplayName("ReflectionMetadataCache 类卸载诊断单元测试")
class ReflectionMetadataCacheUnloadUnitTest {

    /** 探针类的二进制名：由隔离类加载器加载。 */
    private static final String PROBE_BINARY_NAME =
            ReflectionMetadataCacheUnloadUnitTest.class.getName() + "$UnloadProbe";

    /** 回收尝试次数：有限窗口，避免无限期等待 GC。 */
    private static final int COLLECTION_ATTEMPTS = 50;

    /** 回收尝试之间的分配压力块大小（字节），促使软/弱可达对象在本轮被回收。 */
    private static final int PRESSURE_BYTES = 1 << 20;

    /**
     * 探针类：由隔离类加载器加载，只携带可反射读取的字段。
     */
    public static final class UnloadProbe {

        /** 标量字段。 */
        public String text = "probe";

        /** 嵌套对象字段：快照必须递归脱水而非保留引用。 */
        public UnloadNested nested = new UnloadNested();
    }

    /**
     * 探针的嵌套对象字段类型。
     */
    public static final class UnloadNested {

        /** 嵌套字段值。 */
        public String value = "nested";
    }

    @Nested
    @DisplayName("隔离加载器诊断")
    class Diagnosis {

        @Test
        @DisplayName("隔离类加载器应能从测试类路径加载探针类，且与当前加载器的类不同一")
        void isolatedLoader_shouldLoadTheProbeClassFromTheTestClasspath() throws Exception {
            final Path classesRoot = testClassesRoot();
            try (URLClassLoader loader = isolatedLoader(classesRoot)) {
                final Class<?> probeType = loader.loadClass(PROBE_BINARY_NAME);

                assertThat(probeType.getName()).isEqualTo(PROBE_BINARY_NAME);
                assertThat(probeType).isNotSameAs(Class.forName(PROBE_BINARY_NAME));
                assertThat(probeType.getClassLoader()).isSameAs(loader);
            }
        }

        @Test
        @DisplayName("共享缓存应按类复用外来类元数据并提供已准备的访问")
        void sharedCache_shouldServeForeignClassMetadata() throws Exception {
            final Class<?> probeType;
            try (URLClassLoader loader = isolatedLoader(testClassesRoot())) {
                probeType = loader.loadClass(PROBE_BINARY_NAME);

                final ReflectionTypeMetadata metadata = ReflectionMetadataCache.SHARED.get(probeType);

                assertThat(metadata).isSameAs(ReflectionMetadataCache.SHARED.get(probeType));
                assertThat(metadata.size()).isEqualTo(2);
                assertThat(metadata.access(0).fieldName()).isEqualTo("text");
                assertThat(metadata.access(1).fieldName()).isEqualTo("nested");
            }
        }

        @Test
        @DisplayName("快照外来类实例应脱水字段值，缓存与快照都不保留业务实例")
        void snapshot_shouldDehydrateTheForeignInstance() throws Exception {
            final Class<?> probeType;
            try (URLClassLoader loader = isolatedLoader(testClassesRoot())) {
                probeType = loader.loadClass(PROBE_BINARY_NAME);
                final Object instance = probeType.getDeclaredConstructor().newInstance();
                final ValueNodeSnapshotStrategy strategy =
                        new ValueNodeSnapshotStrategy(TrackingConfiguration.empty());

                final ValueNodeSnapshot snapshot = strategy.createSnapshot(instance);
                final ObjectNode node = (ObjectNode) snapshot.getSnapshotData();

                assertThat(node.field("text")).isEqualTo(new PrimitiveNode("probe"));
                assertThat(((ObjectNode) node.field("nested")).field("value"))
                        .isEqualTo(new PrimitiveNode("nested"));
            }
        }

        @Test
        @DisplayName("快照后释放强引用，探针实例与类加载器应在有界回收窗口内不可达")
        void releasedProbeAndLoader_shouldBecomeUnreachableWithinTheBoundedWindow() throws Exception {
            final DiagnosisResult diagnosis = diagnoseAndRelease(testClassesRoot());

            assertThat(awaitCollection(diagnosis.instanceReference()))
                    .as("业务实例必须可回收（缓存与快照都不保留业务实例）")
                    .isTrue();
            assertThat(awaitCollection(diagnosis.loaderReference()))
                    .as("类加载器应在 %d 次回收尝试内不可达（共享缓存不建立强键全局 Map）",
                            COLLECTION_ATTEMPTS)
                    .isTrue();
        }

        @Test
        @DisplayName("隔离加载器路径不存在时应显式失败，不静默当作卸载成功")
        void missingClassesRoot_shouldFailLoudly() {
            assertThatThrownBy(() -> diagnoseAndRelease(Path.of("target", "classes-that-do-not-exist")))
                    .isInstanceOf(ClassNotFoundException.class);
        }
    }

    /**
     * 在隔离加载器中加载探针、快照其实例并释放全部强引用，返回仅含弱引用的诊断结果。
     * <p>
     * 断言在方法内部完成，方法返回后其局部变量（类、实例、元数据、快照）不再持有强引用，
     * 因此调用方观察到的是缓存与 JDK 类元数据留下的引用关系。
     *
     * @param classesRoot 隔离加载器的类路径根目录
     * @return 仅持有强引用释放后弱引用的诊断结果
     * @throws ClassNotFoundException 如果类路径下不存在探针类
     * @throws Exception              如果探针实例创建或时间读取失败
     */
    private static DiagnosisResult diagnoseAndRelease(final Path classesRoot) throws Exception {
        final URLClassLoader loader = isolatedLoader(classesRoot);
        final Class<?> probeType = loader.loadClass(PROBE_BINARY_NAME);
        final Object instance = probeType.getDeclaredConstructor().newInstance();
        final ValueNodeSnapshotStrategy strategy = new ValueNodeSnapshotStrategy(TrackingConfiguration.empty());

        final ValueNodeSnapshot snapshot = strategy.createSnapshot(instance);
        final ReflectionTypeMetadata metadata = ReflectionMetadataCache.SHARED.get(probeType);

        assertThat(snapshot.getSnapshotData()).isInstanceOf(ObjectNode.class);
        assertThat(metadata.size()).isEqualTo(2);
        assertThat(metadata.access(0).fieldName()).isEqualTo("text");

        loader.close();
        final WeakReference<Object> instanceReference = new WeakReference<>(instance);
        final WeakReference<ClassLoader> loaderReference = new WeakReference<>(loader);
        return new DiagnosisResult(instanceReference, loaderReference);
    }

    /**
     * 创建隔离类加载器：父加载器取平台加载器，因此探针类只由本加载器定义，与当前加载器的类不同一。
     *
     * @param classesRoot 类路径根目录
     * @return 隔离类加载器
     * @throws Exception 如果目录 URL 构造失败
     */
    private static URLClassLoader isolatedLoader(final Path classesRoot) throws Exception {
        final URL[] urls = {classesRoot.toUri().toURL()};
        return new URLClassLoader(urls, ClassLoader.getPlatformClassLoader());
    }

    /**
     * 测试类路径根目录：surefire 的工作目录为模块目录。
     *
     * @return 测试类输出目录
     */
    private static Path testClassesRoot() {
        return Path.of("target", "test-classes");
    }

    /**
     * 在有界窗口内观察弱引用是否变为不可达：每轮触发回收并施加分配压力，单轮未回收不判为泄漏。
     *
     * @param reference 待观察的弱引用
     * @return 窗口内不可达返回 true
     */
    private static boolean awaitCollection(final WeakReference<?> reference) {
        for (int attempt = 0; attempt < COLLECTION_ATTEMPTS; attempt++) {
            if (reference.refersTo(null)) {
                return true;
            }
            System.gc();
            applyPressure();
        }
        return reference.refersTo(null);
    }

    /**
     * 施加一次分配压力，促使已不可达的对象在本轮回收。
     */
    private static void applyPressure() {
        final byte[] pressure = new byte[PRESSURE_BYTES];
        pressure[0] = 1;
    }

    /**
     * 类卸载诊断结果：只保留强引用释放后的弱引用，避免诊断自身延长目标生命周期。
     *
     * @param instanceReference 探针实例的弱引用
     * @param loaderReference   隔离类加载器的弱引用
     */
    private record DiagnosisResult(WeakReference<Object> instanceReference,
                                   WeakReference<ClassLoader> loaderReference) {
    }
}