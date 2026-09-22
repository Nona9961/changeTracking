package com.nona.changeTracking.bench.env;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link EnvironmentRecordCollector} with a hand written probe
 * substitute: happy path, explicit unknown host, empty lists and probe failures.
 */
@DisplayName("EnvironmentRecordCollector 环境采集单元测试")
class EnvironmentRecordCollectorUnitTest {

    @Test
    @DisplayName("采集应原样承接探针的 JDK 版本、JVM 参数、GC 收集器与机器标识")
    void collect_shouldCopyEveryProbeValue() {
        final FakeProbe probe = new FakeProbe();

        final EnvironmentRecord record = EnvironmentRecordCollector.collect(probe);

        assertThat(record.jdkVersion()).isEqualTo("25.0.4");
        assertThat(record.jvmArguments()).containsExactly("-Xmx2g", "-XX:+UseG1GC");
        assertThat(record.gcCollectors()).containsExactly("G1 Young Generation", "G1 Old Generation");
        assertThat(record.machine().hostName()).isEqualTo("bench-host");
        assertThat(record.machine().availableProcessors()).isEqualTo(8);
        assertThat(record.machine().maxMemoryBytes()).isEqualTo(2_147_483_648L);
    }

    @Test
    @DisplayName("主机名缺失（null / 空白）应落到显式未知标记，不抛异常")
    void collect_withMissingHostName_shouldUseExplicitUnknownMarker() {
        final FakeProbe nullHost = new FakeProbe();
        nullHost.hostName = null;
        final FakeProbe blankHost = new FakeProbe();
        blankHost.hostName = "   ";

        assertThat(EnvironmentRecordCollector.collect(nullHost).machine().hostName())
                .isEqualTo(MachineIdentity.UNKNOWN_HOST_NAME);
        assertThat(EnvironmentRecordCollector.collect(blankHost).machine().hostName())
                .isEqualTo(MachineIdentity.UNKNOWN_HOST_NAME);
    }

    @Test
    @DisplayName("JVM 参数与 GC 收集器为空列表时应保持显式空列表")
    void collect_withEmptyLists_shouldKeepEmptyLists() {
        final FakeProbe probe = new FakeProbe();
        probe.jvmArguments = List.of();
        probe.gcCollectors = List.of();

        final EnvironmentRecord record = EnvironmentRecordCollector.collect(probe);

        assertThat(record.jvmArguments()).isEmpty();
        assertThat(record.gcCollectors()).isEmpty();
    }

    @Test
    @DisplayName("采集结果应与探针后续变更隔离（防御拷贝）")
    void collect_shouldBeIndependentOfProbeMutationAfterwards() {
        final FakeProbe probe = new FakeProbe();
        final EnvironmentRecord record = EnvironmentRecordCollector.collect(probe);

        probe.jvmArguments = List.of("-Xmx4g");
        probe.gcCollectors = List.of("Serial");

        assertThat(record.jvmArguments()).containsExactly("-Xmx2g", "-XX:+UseG1GC");
        assertThat(record.gcCollectors()).containsExactly("G1 Young Generation", "G1 Old Generation");
    }

    @Test
    @DisplayName("JDK 版本为 null 或空白应拒绝采集")
    void collect_withMissingJdkVersion_shouldReject() {
        final FakeProbe nullVersion = new FakeProbe();
        nullVersion.jdkVersion = null;
        final FakeProbe blankVersion = new FakeProbe();
        blankVersion.jdkVersion = "  ";

        assertThatThrownBy(() -> EnvironmentRecordCollector.collect(nullVersion))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jdkVersion");
        assertThatThrownBy(() -> EnvironmentRecordCollector.collect(blankVersion))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jdkVersion");
    }

    @Test
    @DisplayName("JVM 参数或 GC 收集器为 null 应拒绝采集")
    void collect_withNullLists_shouldReject() {
        final FakeProbe nullArguments = new FakeProbe();
        nullArguments.jvmArguments = null;
        final FakeProbe nullCollectors = new FakeProbe();
        nullCollectors.gcCollectors = null;

        assertThatThrownBy(() -> EnvironmentRecordCollector.collect(nullArguments))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jvmArguments");
        assertThatThrownBy(() -> EnvironmentRecordCollector.collect(nullCollectors))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("gcCollectors");
    }

    @Test
    @DisplayName("处理器数非正数应拒绝采集")
    void collect_withNonPositiveProcessors_shouldReject() {
        final FakeProbe zeroProcessors = new FakeProbe();
        zeroProcessors.availableProcessors = 0;
        final FakeProbe negativeProcessors = new FakeProbe();
        negativeProcessors.availableProcessors = -1;

        assertThatThrownBy(() -> EnvironmentRecordCollector.collect(zeroProcessors))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("availableProcessors");
        assertThatThrownBy(() -> EnvironmentRecordCollector.collect(negativeProcessors))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("availableProcessors");
    }

    @Test
    @DisplayName("最大堆非正数应拒绝采集")
    void collect_withNonPositiveMaxMemory_shouldReject() {
        final FakeProbe zeroMemory = new FakeProbe();
        zeroMemory.maxMemoryBytes = 0;
        final FakeProbe negativeMemory = new FakeProbe();
        negativeMemory.maxMemoryBytes = -1;

        assertThatThrownBy(() -> EnvironmentRecordCollector.collect(zeroMemory))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("maxMemoryBytes");
        assertThatThrownBy(() -> EnvironmentRecordCollector.collect(negativeMemory))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("maxMemoryBytes");
    }

    @Test
    @DisplayName("探针自身失败应原样传播，不吞异常、不包装")
    void collect_withFailingProbe_shouldPropagateOriginalFailure() {
        final FakeProbe probe = new FakeProbe();
        final IllegalStateException failure = new IllegalStateException("probe failure");
        probe.failure = failure;

        assertThatThrownBy(() -> EnvironmentRecordCollector.collect(probe)).isSameAs(failure);
    }

    @Test
    @DisplayName("探针为 null 应拒绝")
    void collect_withNullProbe_shouldReject() {
        assertThatThrownBy(() -> EnvironmentRecordCollector.collect(null))
                .isInstanceOf(NullPointerException.class);
    }

    private static final class FakeProbe implements EnvironmentProbe {

        private String jdkVersion = "25.0.4";
        private List<String> jvmArguments = List.of("-Xmx2g", "-XX:+UseG1GC");
        private List<String> gcCollectors = List.of("G1 Young Generation", "G1 Old Generation");
        private String hostName = "bench-host";
        private int availableProcessors = 8;
        private long maxMemoryBytes = 2_147_483_648L;
        private RuntimeException failure;

        @Override
        public String jdkVersion() {
            failIfRequested();
            return jdkVersion;
        }

        @Override
        public List<String> jvmArguments() {
            failIfRequested();
            return jvmArguments;
        }

        @Override
        public List<String> gcCollectors() {
            failIfRequested();
            return gcCollectors;
        }

        @Override
        public String hostName() {
            failIfRequested();
            return hostName;
        }

        @Override
        public int availableProcessors() {
            failIfRequested();
            return availableProcessors;
        }

        @Override
        public long maxMemoryBytes() {
            failIfRequested();
            return maxMemoryBytes;
        }

        private void failIfRequested() {
            if (failure != null) {
                throw failure;
            }
        }
    }
}
