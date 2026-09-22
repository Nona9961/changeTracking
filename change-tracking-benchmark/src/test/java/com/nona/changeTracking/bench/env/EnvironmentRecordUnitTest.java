package com.nona.changeTracking.bench.env;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link EnvironmentRecord}: JSON projection of the four
 * environment facts, empty lists, escaping and determinism.
 */
@DisplayName("EnvironmentRecord 环境记录单元测试")
class EnvironmentRecordUnitTest {

    @Test
    @DisplayName("toJson 应包含 AC4 的四项环境事实")
    void toJson_shouldContainAllEnvironmentFacts() {
        final String json = fullRecord().toJson();

        assertThat(json).contains("\"jdkVersion\":\"25.0.4\"");
        assertThat(json).contains("\"jvmArguments\":[\"-Xmx2g\",\"-XX:+UseG1GC\"]");
        assertThat(json).contains("\"gcCollectors\":[\"G1 Young Generation\",\"G1 Old Generation\"]");
        assertThat(json).contains("\"machine\":");
        assertThat(json).contains("\"hostName\":\"bench-host\"");
        assertThat(json).contains("\"availableProcessors\":8");
        assertThat(json).contains("\"maxMemoryBytes\":2147483648");
    }

    @Test
    @DisplayName("空参数列表与空 GC 收集器应输出空 JSON 数组")
    void toJson_withEmptyLists_shouldEmitEmptyArrays() {
        final EnvironmentRecord record = new EnvironmentRecord(
                "25.0.4", List.of(), List.of(), new MachineIdentity("bench-host", 8, 1L));

        final String json = record.toJson();

        assertThat(json).contains("\"jvmArguments\":[]");
        assertThat(json).contains("\"gcCollectors\":[]");
    }

    @Test
    @DisplayName("引号、反斜杠与控制字符应被转义")
    void toJson_withSpecialCharacters_shouldEscape() {
        final EnvironmentRecord record = new EnvironmentRecord(
                "25.0.4",
                List.of("a\"b\\c"),
                List.of(),
                new MachineIdentity("host\tname\nline", 1, 1L));

        final String json = record.toJson();

        assertThat(json).contains("a\\\"b\\\\c");
        assertThat(json).contains("host\\tname\\nline");
        assertThat(json).doesNotContain("\"host\tname");
    }

    @Test
    @DisplayName("同一记录两次序列化结果应一致")
    void toJson_calledTwice_shouldBeDeterministic() {
        final EnvironmentRecord record = fullRecord();

        assertThat(record.toJson()).isEqualTo(record.toJson());
    }

    private static EnvironmentRecord fullRecord() {
        return new EnvironmentRecord(
                "25.0.4",
                List.of("-Xmx2g", "-XX:+UseG1GC"),
                List.of("G1 Young Generation", "G1 Old Generation"),
                new MachineIdentity("bench-host", 8, 2_147_483_648L));
    }
}
