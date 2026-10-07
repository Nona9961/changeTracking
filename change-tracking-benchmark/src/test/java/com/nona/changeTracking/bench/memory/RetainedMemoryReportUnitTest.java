package com.nona.changeTracking.bench.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link RetainedMemoryReport}: the stable line form of the rendered report and the
 * validation of its fields.
 */
@DisplayName("RetainedMemoryReport 保留内存报告单元测试")
class RetainedMemoryReportUnitTest {

    @Test
    @DisplayName("报告应渲染为以固定键开头的稳定行")
    void render_shouldProduceStableKeyedLines() {
        final RetainedFootprint footprint = new RetainedFootprint(48L, 2, List.of(
                new ClassFootprint("java.lang.String", 2, 48L)));
        final RetainedMemoryReport report =
                new RetainedMemoryReport(RetainedMemoryScenario.CALCULATE_ONLY, "deepChain", footprint);

        assertThat(report.render()).containsExactly(
                "scenario=calculateOnly",
                "shape=deepChain",
                "heldResults=calculatedSet",
                "retainedBytes=48",
                "retainedObjects=2",
                "retainedClass=java.lang.String count=2 bytes=48");
    }

    @Test
    @DisplayName("重复获取场景应在报告头列出两个完整视图令牌")
    void render_shouldListEveryHeldResultOfTheScenario() {
        final RetainedMemoryReport report = new RetainedMemoryReport(
                RetainedMemoryScenario.REPEATED_ACQUIRE, "deepChain",
                new RetainedFootprint(0L, 0, List.of()));

        assertThat(report.render()).contains("heldResults=calculatedSet,fullView,fullView");
    }

    @Test
    @DisplayName("报告应校验场景、形状与脚印")
    void report_shouldValidateItsFields() {
        assertThatThrownBy(() -> new RetainedMemoryReport(null, "deepChain", new RetainedFootprint(0L, 0, List.of())))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RetainedMemoryReport(RetainedMemoryScenario.CALCULATE_ONLY, "  ",
                new RetainedFootprint(0L, 0, List.of())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetainedMemoryReport(RetainedMemoryScenario.CALCULATE_ONLY, "deepChain", null))
                .isInstanceOf(NullPointerException.class);
    }
}
