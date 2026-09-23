package com.nona.changeTracking.bench.result;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link BenchmarkModuleDirectory} 的单元测试：模块目录由入口类所在的类路径位置推断——
 * 位置是模块 {@code target} 目录下的 jar 或类目录时，模块目录即该 {@code target} 的父目录；
 * 位置不在 {@code target} 之下时拒绝，不猜路径。
 * <p>
 * 前置状态由各用例自建：合成布局用 {@link TempDir} 构造，不依赖本机构建产物（推断规则只读位置
 * 形态、不访问文件系统，因此路径无需存在）；真实布局由
 * {@link BenchmarkModuleDirectory#resolve()} 一例覆盖，其期望值以 surefire 工作目录（本模块目录，
 * 本模块测试的既成约定）为独立真值，并另以「该目录含模块 pom」交叉核对。
 */
@DisplayName("BenchmarkModuleDirectory 模块目录推断单元测试")
class BenchmarkModuleDirectoryUnitTest {

    /** 本模块目录，surefire 的工作目录（本模块测试的既成约定）。 */
    private static final Path MODULE_DIRECTORY = Path.of("").toAbsolutePath();

    @Test
    @DisplayName("类目录形态的类路径位置应推断出模块目录")
    void ofClasspathLocation_withClassesDirectory_shouldResolveModuleDirectory(@TempDir final Path directory) {
        final Path moduleDirectory = directory.resolve("change-tracking-benchmark");
        final Path classpathLocation = moduleDirectory.resolve("target").resolve("classes");

        assertThat(BenchmarkModuleDirectory.ofClasspathLocation(classpathLocation)).isEqualTo(moduleDirectory);
    }

    @Test
    @DisplayName("jar 形态的类路径位置应推断出模块目录")
    void ofClasspathLocation_withJarInTarget_shouldResolveModuleDirectory(@TempDir final Path directory) {
        final Path moduleDirectory = directory.resolve("change-tracking-benchmark");
        final Path classpathLocation = moduleDirectory.resolve("target").resolve("benchmarks.jar");

        assertThat(BenchmarkModuleDirectory.ofClasspathLocation(classpathLocation)).isEqualTo(moduleDirectory);
    }

    @Test
    @DisplayName("真实类路径位置应推断出本模块目录，且该目录含模块 pom")
    void resolve_shouldLocateTheRealModuleDirectory() {
        final Path moduleDirectory = BenchmarkModuleDirectory.resolve();

        assertThat(moduleDirectory).isEqualTo(MODULE_DIRECTORY);
        assertThat(Files.isRegularFile(moduleDirectory.resolve("pom.xml")))
                .as("the resolved directory is the module directory holding the module pom")
                .isTrue();
    }

    @Test
    @DisplayName("位置为 target 目录自身时应拒绝，消息含该位置")
    void ofClasspathLocation_withTargetDirectoryItself_shouldReject(@TempDir final Path directory) {
        final Path classpathLocation = directory.resolve("change-tracking-benchmark").resolve("target");

        assertThatThrownBy(() -> BenchmarkModuleDirectory.ofClasspathLocation(classpathLocation))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(classpathLocation.toString());
    }

    @Test
    @DisplayName("位置为 target 之下的更深条目时应拒绝，消息含该位置")
    void ofClasspathLocation_belowTheDirectChildrenOfTarget_shouldReject(@TempDir final Path directory) {
        final Path classpathLocation = directory.resolve("change-tracking-benchmark").resolve("target")
                .resolve("classes").resolve("nested").resolve("module.jar");

        assertThatThrownBy(() -> BenchmarkModuleDirectory.ofClasspathLocation(classpathLocation))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(classpathLocation.toString());
    }

    @Test
    @DisplayName("相对类路径位置应拒绝，不返回 null")
    void ofClasspathLocation_withRelativeLocation_shouldReject() {
        assertThatThrownBy(() -> BenchmarkModuleDirectory.ofClasspathLocation(Path.of("target", "classes")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("位置为 null 应拒绝")
    void ofClasspathLocation_withNullLocation_shouldReject() {
        assertThatThrownBy(() -> BenchmarkModuleDirectory.ofClasspathLocation(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("位置不在 target 目录下时应拒绝，消息含该位置")
    void ofClasspathLocation_outsideATargetDirectory_shouldReject(@TempDir final Path directory) {
        final Path classpathLocation = directory.resolve("relocated").resolve("benchmarks.jar");

        assertThatThrownBy(() -> BenchmarkModuleDirectory.ofClasspathLocation(classpathLocation))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(classpathLocation.toString());
    }
}
