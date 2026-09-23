package com.nona.changeTracking.bench.result;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link CompareResultsMain}: the comparison entry point requires both result files
 * and rejects an incomplete command line.
 */
@DisplayName("CompareResultsMain 对比入口单元测试")
class CompareResultsMainUnitTest {

    @Test
    @DisplayName("两个结果文件应被解析为对比请求")
    void parse_withBothResultFiles_shouldBuildRequest() {
        final CompareResultsMain.Request request = CompareResultsMain.parse(new String[] {
                "--first", "benchmark/results/1.0/jmh-result.json",
                "--second", "benchmark/results/1.1/jmh-result.json"});

        assertThat(request.firstResultFile()).isEqualTo(Path.of("benchmark", "results", "1.0", "jmh-result.json"));
        assertThat(request.secondResultFile()).isEqualTo(Path.of("benchmark", "results", "1.1", "jmh-result.json"));
    }

    @Test
    @DisplayName("选项顺序不应影响解析结果")
    void parse_withReorderedOptions_shouldParseTheSameRequest() {
        final CompareResultsMain.Request request = CompareResultsMain.parse(new String[] {
                "--second", "second.json", "--first", "first.json"});

        assertThat(request.firstResultFile()).isEqualTo(Path.of("first.json"));
        assertThat(request.secondResultFile()).isEqualTo(Path.of("second.json"));
    }

    @Test
    @DisplayName("缺少 --first 应拒绝")
    void parse_withoutFirstResultFile_shouldReject() {
        assertThatThrownBy(() -> CompareResultsMain.parse(new String[] {"--second", "second.json"}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("缺少 --second 应拒绝")
    void parse_withoutSecondResultFile_shouldReject() {
        assertThatThrownBy(() -> CompareResultsMain.parse(new String[] {"--first", "first.json"}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("空白路径应拒绝")
    void parse_withBlankPath_shouldReject() {
        assertThatThrownBy(() -> CompareResultsMain.parse(new String[] {"--first", "  ", "--second", "second.json"}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("--second 没有取值应拒绝")
    void parse_withSecondWithoutValue_shouldReject() {
        assertThatThrownBy(() -> CompareResultsMain.parse(new String[] {"--first", "first.json", "--second"}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("未知选项应拒绝")
    void parse_withUnknownOption_shouldReject() {
        assertThatThrownBy(() -> CompareResultsMain.parse(new String[] {
                "--first", "first.json", "--second", "second.json", "--third", "third.json"}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("参数数组为 null 应拒绝")
    void parse_withNullArguments_shouldReject() {
        assertThatThrownBy(() -> CompareResultsMain.parse(null))
                .isInstanceOf(NullPointerException.class);
    }
}