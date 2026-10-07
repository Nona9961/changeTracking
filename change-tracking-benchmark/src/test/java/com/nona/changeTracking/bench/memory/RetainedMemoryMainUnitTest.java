package com.nona.changeTracking.bench.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link RetainedMemoryMain}: the command line parsing of the requested retention
 * scenarios.
 */
@DisplayName("RetainedMemoryMain 命令行解析单元测试")
class RetainedMemoryMainUnitTest {

    @Test
    @DisplayName("无 --scenario 时应按声明顺序测量全部场景")
    void parse_withoutScenarioOption_shouldDefaultToEveryScenario() {
        final RetainedMemoryMain.Request request = RetainedMemoryMain.parse(new String[0]);

        assertThat(request.scenarios()).containsExactly(RetainedMemoryScenario.values());
    }

    @Test
    @DisplayName("单个 --scenario 应只选该场景，重复选项应按请求顺序累积")
    void parse_withScenarioOptions_shouldAccumulateInRequestOrder() {
        final RetainedMemoryMain.Request single =
                RetainedMemoryMain.parse(new String[]{"--scenario", "leafOnly"});
        final RetainedMemoryMain.Request repeated = RetainedMemoryMain.parse(
                new String[]{"--scenario", "leafOnly", "--scenario", "fullView"});

        assertThat(single.scenarios()).containsExactly(RetainedMemoryScenario.LEAF_ONLY);
        assertThat(repeated.scenarios())
                .containsExactly(RetainedMemoryScenario.LEAF_ONLY, RetainedMemoryScenario.FULL_VIEW);
    }

    @Test
    @DisplayName("未知选项、缺失取值、未知场景与 null 参数应被拒绝")
    void parse_invalidArguments_shouldBeRejected() {
        assertThatThrownBy(() -> RetainedMemoryMain.parse(new String[]{"--unknown"}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RetainedMemoryMain.parse(new String[]{"--scenario"}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RetainedMemoryMain.parse(new String[]{"--scenario", "unknown"}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RetainedMemoryMain.parse(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Request 应拒绝 null 场景列表")
    void request_withNullScenarioList_shouldBeRejected() {
        assertThatThrownBy(() -> new RetainedMemoryMain.Request(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("解析请求应与等价直接构造的请求等值，并保持请求顺序")
    void request_shouldEqualTheDirectlyBuiltRequestInRequestOrder() {
        final RetainedMemoryMain.Request parsed = RetainedMemoryMain.parse(
                new String[]{"--scenario", "fullView", "--scenario", "calculateOnly"});
        final RetainedMemoryMain.Request directlyBuilt = new RetainedMemoryMain.Request(
                List.of(RetainedMemoryScenario.FULL_VIEW, RetainedMemoryScenario.CALCULATE_ONLY));

        assertThat(parsed).isEqualTo(directlyBuilt);
        assertThat(parsed.scenarios())
                .containsExactly(RetainedMemoryScenario.FULL_VIEW, RetainedMemoryScenario.CALCULATE_ONLY);
    }
}
