package com.nona.changeTracking.bench.result;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ArchiveDirectoryName}: the name is the caller supplied run identifier (when
 * one was given) followed by the UTC timestamp of the archival, the timestamp is normalised to UTC
 * and truncated to the second, and a taken name derives its next candidate through
 * {@link ArchiveDirectoryName#withSequence(int)}.
 * <p>
 * The instants below are inputs of the naming rule, never assertions about the current time: every
 * expectation is derived from the given instant (shape of the rendered timestamp, equality of two
 * spellings of one instant, truncation inside a second, difference between adjacent seconds, date
 * part of the UTC day the instant belongs to).
 */
@DisplayName("ArchiveDirectoryName 归档目录名单元测试")
class ArchiveDirectoryNameUnitTest {

    /** Shape of the rendered timestamp: eight date digits, {@code T}, six time digits, {@code Z}. */
    private static final String TIMESTAMP_PATTERN = "\\d{8}T\\d{6}Z";

    /** Independent rendering of the UTC timestamp, used as the expected value of the naming rule. */
    private static final DateTimeFormatter UTC_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    @Test
    @DisplayName("提供了标识时目录名应为 标识-UTC 时间戳")
    void of_withIdentifier_shouldPrefixTheTimestamp() {
        final ArchiveDirectoryName name = ArchiveDirectoryName.of(Optional.of(runId()), utcInstant());

        assertThat(name.name()).matches(runId().value() + "-" + TIMESTAMP_PATTERN);
        assertThat(name.name()).isEqualTo(runId().value() + "-" + UTC_TIMESTAMP.format(utcInstant()));
    }

    @Test
    @DisplayName("未提供标识时目录名应只有 UTC 时间戳")
    void of_withoutIdentifier_shouldUseTheTimestampAlone() {
        final ArchiveDirectoryName name = ArchiveDirectoryName.of(Optional.empty(), utcInstant());

        assertThat(name.name()).matches(TIMESTAMP_PATTERN);
        assertThat(name.name()).doesNotContain(runId().value());
    }

    @Test
    @DisplayName("仅有时间戳的目录名与未提供标识的命名一致")
    void timestampOnly_shouldMatchTheNamingWithoutIdentifier() {
        final ArchiveDirectoryName timestampOnly = ArchiveDirectoryName.timestampOnly(utcInstant());

        assertThat(timestampOnly.name()).matches(TIMESTAMP_PATTERN);
        assertThat(timestampOnly.name()).isEqualTo(UTC_TIMESTAMP.format(utcInstant()));
        assertThat(timestampOnly).isEqualTo(ArchiveDirectoryName.of(Optional.empty(), utcInstant()));
    }

    @Test
    @DisplayName("时间戳应按 UTC 渲染，不随本地时区偏移")
    void name_shouldRenderTheTimestampInUtc() {
        final Instant instant = utcInstant();

        final ArchiveDirectoryName name = ArchiveDirectoryName.of(Optional.empty(), instant);

        assertThat(name.name()).isEqualTo(UTC_TIMESTAMP.format(instant));
        assertThat(name.name()).endsWith("Z");
        assertThat(name.name()).hasSize(UTC_TIMESTAMP.format(instant).length());
    }

    @Test
    @DisplayName("同一秒内的不同纳秒应得到相同目录名（秒精度）")
    void name_withFractionalSecond_shouldTruncateToTheSecond() {
        final ArchiveDirectoryName whole = ArchiveDirectoryName.of(Optional.empty(), utcInstant());

        final ArchiveDirectoryName fractional =
                ArchiveDirectoryName.of(Optional.empty(), Instant.parse("2026-09-22T15:05:01.999999999Z"));

        assertThat(fractional).isEqualTo(whole);
    }

    @Test
    @DisplayName("相邻秒应得到不同目录名（秒精度边界）")
    void name_withAdjacentSecond_shouldDiffer() {
        final ArchiveDirectoryName atSecond = ArchiveDirectoryName.of(Optional.empty(), utcInstant());

        final ArchiveDirectoryName nextSecond =
                ArchiveDirectoryName.of(Optional.empty(), Instant.parse("2026-09-22T15:05:02Z"));

        assertThat(nextSecond).isNotEqualTo(atSecond);
        assertThat(nextSecond.name()).isNotEqualTo(atSecond.name());
    }

    @Test
    @DisplayName("日期位应取 UTC 日，而非本地日")
    void name_shouldUseTheUtcDay() {
        final Instant lastSecondOfTheUtcDay = Instant.parse("2026-09-22T23:59:59Z");

        final ArchiveDirectoryName name = ArchiveDirectoryName.of(Optional.empty(), lastSecondOfTheUtcDay);

        assertThat(name.name()).startsWith(utcDatePartOf(lastSecondOfTheUtcDay));
    }

    @Test
    @DisplayName("极早与极晚瞬时均应可渲染（取值范围边界）")
    void name_withExtremeInstants_shouldRender() {
        final ArchiveDirectoryName epoch = ArchiveDirectoryName.of(Optional.empty(), Instant.EPOCH);
        final Instant lastRepresentableSecond = Instant.parse("9999-12-31T23:59:59Z");
        final ArchiveDirectoryName latest = ArchiveDirectoryName.of(Optional.empty(), lastRepresentableSecond);

        assertThat(epoch.name()).matches(TIMESTAMP_PATTERN).startsWith(utcDatePartOf(Instant.EPOCH));
        assertThat(latest.name()).matches(TIMESTAMP_PATTERN).startsWith(utcDatePartOf(lastRepresentableSecond));
    }

    @Test
    @DisplayName("序号应追加为 目录名-<序号>（冲突候选名）")
    void withSequence_shouldAppendTheSequenceNumber() {
        final ArchiveDirectoryName baseName = ArchiveDirectoryName.of(Optional.of(runId()), utcInstant());

        final ArchiveDirectoryName second = baseName.withSequence(2);
        final ArchiveDirectoryName third = baseName.withSequence(3);

        assertThat(second.name()).isEqualTo(baseName.name() + "-2");
        assertThat(third.name()).isEqualTo(baseName.name() + "-3");
        // The base name never carries the sequence number as a suffix: the "-2" inside it is the
        // separator between identifier and timestamp standing next to the leading year digit, so the
        // assertion is on the suffix rather than on containment. Both spellings above keep the
        // discriminating power: an implementation that wrote the sequence into the base name would
        // make the base name end with "-2".
        assertThat(baseName.name()).doesNotEndWith("-2");
    }

    @Test
    @DisplayName("序号小于 2 应被拒绝（基础名即首次尝试）")
    void withSequence_belowTwo_shouldReject() {
        final ArchiveDirectoryName baseName = ArchiveDirectoryName.of(Optional.of(runId()), utcInstant());

        assertThatThrownBy(() -> baseName.withSequence(1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> baseName.withSequence(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> baseName.withSequence(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("越界目录名应被拒绝：空白、分隔符、穿越序列、首尾空白")
    void constructor_withUnsafeName_shouldReject() {
        assertThatThrownBy(() -> new ArchiveDirectoryName(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ArchiveDirectoryName("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ArchiveDirectoryName("   ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ArchiveDirectoryName(" 1.0-20260922T150501Z"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ArchiveDirectoryName("1.0/20260922T150501Z"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ArchiveDirectoryName("1.0\\20260922T150501Z"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ArchiveDirectoryName(".."))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ArchiveDirectoryName("."))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("null 标识或 null 瞬时应被拒绝")
    void of_withNullArguments_shouldReject() {
        assertThatThrownBy(() -> ArchiveDirectoryName.of(null, utcInstant()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ArchiveDirectoryName.of(Optional.of(runId()), null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ArchiveDirectoryName.timestampOnly(null))
                .isInstanceOf(NullPointerException.class);
    }

    /**
     * Creates the run identifier used by these tests.
     *
     * @return the identifier of the archived run
     */
    private static BenchmarkRunId runId() {
        return new BenchmarkRunId("1.0-SNAPSHOT");
    }

    /**
     * Returns the instant used by these tests as the input of the naming rule.
     *
     * @return the fixed instant of the archival
     */
    private static Instant utcInstant() {
        return Instant.parse("2026-09-22T15:05:01Z");
    }

    /**
     * Derives the UTC date part of an instant from the instant itself, so no date literal enters the
     * assertions.
     *
     * @param instant the instant to render
     * @return the eight digit UTC date of the instant
     */
    private static String utcDatePartOf(final Instant instant) {
        return instant.toString().substring(0, 10).replace("-", "");
    }
}