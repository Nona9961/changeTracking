package com.nona.changeTracking.bench.result;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;

/**
 * Directory name of one archived benchmark run: the run identifier supplied by the caller (when one
 * was given) followed by the UTC timestamp of the archival.
 * <p>
 * Naming rule: {@code <runId>-<timestamp>} when the caller supplied an identifier through
 * {@code --id}, and {@code <timestamp>} alone otherwise. The timestamp is UTC and truncated to the
 * second, so the same instant is named identically on every machine and time zone and two runs of one
 * second share a name. The name is a single path safe segment: it never carries a path separator, a
 * traversal sequence or surrounding whitespace.
 * <p>
 * A name that is already taken in the archive root is not a failure: {@link #withSequence(int)}
 * derives the next candidate name, which the archiver tries until an unused one is found. The name
 * itself never contains the sequence number, so the base name stays derivable from the run.
 *
 * @param name the archived run directory name, one path safe segment
 */
public record ArchiveDirectoryName(String name) {

    /** Formatter rendering the instant of an archival as a UTC timestamp truncated to the second. */
    private static final DateTimeFormatter UTC_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    /**
     * Validates the directory name.
     * <p>
     * A {@code null}, empty or blank name, a name with
     * surrounding whitespace, a name equal to {@code .}, and a name containing {@code /}, {@code \}
     * or the {@code ..} traversal sequence throw {@link IllegalArgumentException}; every other
     * non blank name is kept verbatim.
     *
     * @param name the archived run directory name
     */
    public ArchiveDirectoryName {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("archive directory name must not be blank, got: " + name);
        }
        if (!name.equals(name.strip())) {
            throw new IllegalArgumentException(
                    "archive directory name must not carry surrounding whitespace, got: " + name);
        }
        if (name.contains("/") || name.contains("\\")) {
            throw new IllegalArgumentException("archive directory name must not contain a path separator, got: " + name);
        }
        if (name.contains("..") || ".".equals(name)) {
            throw new IllegalArgumentException("archive directory name must not be a traversal path segment, got: " + name);
        }
    }

    /**
     * Derives the directory name of one archival from the caller supplied identifier and the instant
     * of the archival.
     * <p>
     * An empty identifier means the caller supplied none (no
     * {@code --id} on the command line) and the name is the rendered timestamp alone; a present
     * identifier is rendered verbatim, followed by {@code -} and the timestamp. The timestamp is
     * rendered as {@code yyyyMMdd'T'HHmmss'Z'} in UTC, truncated to the second.
     *
     * @param runId     caller supplied run identifier, {@code Optional.empty()} when none was supplied
     * @param timestamp instant of the archival
     * @return the base directory name of the archival
     * @throws NullPointerException if runId or timestamp is null
     */
    public static ArchiveDirectoryName of(final Optional<BenchmarkRunId> runId, final Instant timestamp) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(timestamp, "timestamp");
        final String renderedTimestamp = UTC_TIMESTAMP.format(timestamp);
        return runId.map(identifier -> new ArchiveDirectoryName(identifier.value() + "-" + renderedTimestamp))
                .orElseGet(() -> new ArchiveDirectoryName(renderedTimestamp));
    }

    /**
     * Derives the directory name of one archival that carries no run identifier.
     * <p>
     * The name is the rendered UTC timestamp alone, which is
     * the naming of {@link #of(Optional, Instant)} with an empty identifier.
     *
     * @param timestamp instant of the archival
     * @return the base directory name of an archival without run identifier
     * @throws NullPointerException if timestamp is null
     */
    public static ArchiveDirectoryName timestampOnly(final Instant timestamp) {
        return of(Optional.empty(), timestamp);
    }

    /**
     * Derives the candidate directory name of one further conflict attempt.
     * <p>
     * The candidate name is this name followed by
     * {@code -} and the sequence number, so the second attempt is {@code -2}, the third {@code -3}
     * and so on. A sequence number below 2 throws {@link IllegalArgumentException}: the base name
     * itself is the first attempt and is never written with a sequence number.
     *
     * @param sequence the conflict attempt number, at least 2
     * @return the candidate name of the given attempt
     * @throws IllegalArgumentException if sequence is below 2
     */
    public ArchiveDirectoryName withSequence(final int sequence) {
        if (sequence < 2) {
            throw new IllegalArgumentException("conflict sequence must be at least 2, got: " + sequence);
        }
        return new ArchiveDirectoryName(name + "-" + sequence);
    }
}