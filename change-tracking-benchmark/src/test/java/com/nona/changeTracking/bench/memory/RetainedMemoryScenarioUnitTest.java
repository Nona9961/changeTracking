package com.nona.changeTracking.bench.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link RetainedMemoryScenario}: the five scenarios, their stable command line tokens
 * and the held results each scenario declares.
 */
@DisplayName("RetainedMemoryScenario 保留场景单元测试")
class RetainedMemoryScenarioUnitTest {

    @Test
    @DisplayName("五个保留场景应各带稳定的命令行令牌")
    void scenarios_shouldCarryStableCommandLineNames() {
        assertThat(RetainedMemoryScenario.CALCULATE_ONLY.commandLineName()).isEqualTo("calculateOnly");
        assertThat(RetainedMemoryScenario.LEAF_ONLY.commandLineName()).isEqualTo("leafOnly");
        assertThat(RetainedMemoryScenario.FULL_VIEW.commandLineName()).isEqualTo("fullView");
        assertThat(RetainedMemoryScenario.REPEATED_ACQUIRE.commandLineName()).isEqualTo("repeatedAcquire");
        assertThat(RetainedMemoryScenario.CALCULATE_AND_LEAF.commandLineName()).isEqualTo("calculateAndLeaf");
        assertThat(RetainedMemoryScenario.values()).hasSize(5);
    }

    @Test
    @DisplayName("每个场景应声明其持有的结果（计算集为公共基线，视图获取逐项列出）")
    void heldResultViews_shouldDeclareTheHeldResults() {
        assertThat(RetainedMemoryScenario.CALCULATE_ONLY.heldResultViews())
                .containsExactly(RetainedMemoryScenario.ResultView.CALCULATED_SET);
        assertThat(RetainedMemoryScenario.LEAF_ONLY.heldResultViews())
                .containsExactly(RetainedMemoryScenario.ResultView.CALCULATED_SET,
                        RetainedMemoryScenario.ResultView.LEAF_VIEW);
        assertThat(RetainedMemoryScenario.FULL_VIEW.heldResultViews())
                .containsExactly(RetainedMemoryScenario.ResultView.CALCULATED_SET,
                        RetainedMemoryScenario.ResultView.FULL_VIEW);
        assertThat(RetainedMemoryScenario.REPEATED_ACQUIRE.heldResultViews())
                .containsExactly(RetainedMemoryScenario.ResultView.CALCULATED_SET,
                        RetainedMemoryScenario.ResultView.FULL_VIEW,
                        RetainedMemoryScenario.ResultView.FULL_VIEW);
        assertThat(RetainedMemoryScenario.CALCULATE_AND_LEAF.heldResultViews())
                .containsExactly(RetainedMemoryScenario.ResultView.CALCULATED_SET,
                        RetainedMemoryScenario.ResultView.FULL_VIEW,
                        RetainedMemoryScenario.ResultView.LEAF_VIEW);
    }

    @Test
    @DisplayName("命令行令牌应能解析回场景，未知令牌与 null 应被拒绝")
    void fromCommandLineName_shouldResolveKnownTokensAndRejectUnknownOnes() {
        assertThat(RetainedMemoryScenario.fromCommandLineName("repeatedAcquire"))
                .isEqualTo(RetainedMemoryScenario.REPEATED_ACQUIRE);
        assertThat(RetainedMemoryScenario.fromCommandLineName("calculateAndLeaf"))
                .isEqualTo(RetainedMemoryScenario.CALCULATE_AND_LEAF);

        assertThatThrownBy(() -> RetainedMemoryScenario.fromCommandLineName("unknown"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RetainedMemoryScenario.fromCommandLineName(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("结果视图应各带稳定的报告令牌")
    void resultViews_shouldCarryStableTokens() {
        assertThat(RetainedMemoryScenario.ResultView.CALCULATED_SET.token()).isEqualTo("calculatedSet");
        assertThat(RetainedMemoryScenario.ResultView.FULL_VIEW.token()).isEqualTo("fullView");
        assertThat(RetainedMemoryScenario.ResultView.LEAF_VIEW.token()).isEqualTo("leafView");
    }
}
