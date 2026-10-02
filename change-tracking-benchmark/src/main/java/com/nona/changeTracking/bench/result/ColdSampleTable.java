package com.nona.changeTracking.bench.result;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Raw sampling table of the cold cache states: one row per measured invocation of the first use
 * protocol, written as one JSON line per row.
 * <p>
 * <b>Why a separate artifact.</b> The cold states are reported as raw samples and stay out of the
 * steady state judgement: the SRS requires the cold time and the target allocation of the first use
 * protocol to be recorded in their own table, several forks sampled, and explicitly forbids feeding
 * an incomplete cold sample into the steady state comparator. This carrier therefore writes its own
 * document shape ({@value #FILE_NAME}) that the JMH result reader and hence
 * {@link BenchmarkResultComparator} cannot consume: a cold row that misses the time or the target
 * allocation is rejected here, and the artifact never reaches the comparator as a JMH result.
 * <p>
 * <b>Row semantics.</b> A row carries the benchmark name, its parameter binding, the protocol tag of
 * the measured state, the time of the target operation as read by a {@code System.nanoTime} pair
 * around it, the thread allocated bytes around the same operation, and the metering overhead the trial
 * measured once, so the facility cost is reported separately from the target allocation of the whole
 * JVM. Rows are appended, because every fork of the single shot protocol is a separate JVM that
 * contributes exactly one row.
 * <p>
 * <b>Steady state boundary.</b> Steady state results keep using the JMH result archive and
 * {@link BenchmarkResultComparator}; this table is never paired, merged or significance judged with
 * them, and it carries no score error or confidence interval, because a raw sample list has no
 * aggregate statistics to correct.
 */
public final class ColdSampleTable {

    /** File name of the raw sampling table. */
    public static final String FILE_NAME = "cold-samples.jsonl";

    /** Field name of the benchmark of one row. */
    private static final String BENCHMARK_FIELD = "benchmark";

    /** Field name of the parameter binding of one row. */
    private static final String PARAMS_FIELD = "params";

    /** Field name of the protocol tag of one row. */
    private static final String PROTOCOL_FIELD = "protocol";

    /** Field name of the time metric of one row. */
    private static final String NANOS_FIELD = "nanosPerOperation";

    /** Field name of the target allocation metric of one row. */
    private static final String ALLOCATED_FIELD = "allocatedBytesPerOperation";

    /** Field name of the metering overhead of one row. */
    private static final String METERING_OVERHEAD_FIELD = "meteringOverheadNanos";

    /** Jackson reader and writer of the raw sampling rows. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Private constructor: this class is a static table entry point.
     */
    private ColdSampleTable() {
    }

    /**
     * Appends one raw sample row to the table in the given directory, creating the directory when
     * missing.
     * <p>
     * The row is validated before anything is written, so an incomplete sample cannot enter the table:
     * the benchmark name and the protocol must not be blank, the time must be finite and positive, and
     * the target allocation as well as the metering overhead must not be negative.
     *
     * @param outputDirectory directory receiving {@value #FILE_NAME}, created when missing
     * @param sample          the raw sample row to append, must not be null
     * @throws NullPointerException     if outputDirectory or sample is null
     * @throws IllegalArgumentException if the sample misses or contradicts the two measured metrics
     * @throws java.io.UncheckedIOException if the directory cannot be created or the row cannot be appended
     */
    public static void append(final Path outputDirectory, final ColdSample sample) {
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        Objects.requireNonNull(sample, "sample");
        requireCompleteMetrics(sample);
        final String row = renderRow(sample) + System.lineSeparator();
        final Path table = outputDirectory.resolve(FILE_NAME);
        try {
            Files.createDirectories(outputDirectory);
            Files.writeString(table, row, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to append a raw sample row to " + table, e);
        }
    }

    /**
     * Reads every raw sample row of the table in file order.
     * <p>
     * A file that does not hold the table shape, or a row that misses one of the two metrics, is
     * rejected instead of being partially returned, so a truncated row can never be reported as a
     * measured sample.
     *
     * @param file the table file to read
     * @return the raw sample rows in file order, empty when the file holds no row
     * @throws NullPointerException         if file is null
     * @throws IllegalStateException        if the file does not hold the table shape or a row is incomplete
     * @throws java.io.UncheckedIOException if the file cannot be read
     */
    public static List<ColdSample> read(final Path file) {
        Objects.requireNonNull(file, "file");
        if (!Files.exists(file)) {
            throw new UncheckedIOException(new FileNotFoundException("Raw sampling table not found: " + file));
        }
        final List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to read the raw sampling table " + file, e);
        }
        final List<ColdSample> rows = new ArrayList<>();
        for (final String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            rows.add(parseRow(file, line));
        }
        return List.copyOf(rows);
    }

    /**
     * Rejects a row that misses or contradicts one of the two measured metrics before it is written.
     *
     * @param sample the row to validate
     * @throws IllegalArgumentException if the time is not finite and positive, the target allocation is
     *                                  negative, or the metering overhead is not finite and not negative
     */
    private static void requireCompleteMetrics(final ColdSample sample) {
        if (!Double.isFinite(sample.nanosPerOperation()) || sample.nanosPerOperation() <= 0.0) {
            throw new IllegalArgumentException("nanosPerOperation must be finite and positive, got: "
                    + sample.nanosPerOperation());
        }
        if (sample.allocatedBytesPerOperation() < 0L) {
            throw new IllegalArgumentException("allocatedBytesPerOperation must not be negative, got: "
                    + sample.allocatedBytesPerOperation());
        }
        if (!Double.isFinite(sample.meteringOverheadNanos()) || sample.meteringOverheadNanos() < 0.0) {
            throw new IllegalArgumentException("meteringOverheadNanos must be finite and not negative, got: "
                    + sample.meteringOverheadNanos());
        }
    }

    /**
     * Renders one row as a JSON object keyed by the record components, so the table stays readable by
     * this carrier alone and cannot be mistaken for a JMH result document.
     *
     * @param sample the row to render
     * @return the JSON object text of the row
     * @throws IllegalStateException if the row cannot be serialized
     */
    private static String renderRow(final ColdSample sample) {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put(BENCHMARK_FIELD, sample.benchmark());
        row.put(PARAMS_FIELD, sample.params());
        row.put(PROTOCOL_FIELD, sample.protocol());
        row.put(NANOS_FIELD, sample.nanosPerOperation());
        row.put(ALLOCATED_FIELD, sample.allocatedBytesPerOperation());
        row.put(METERING_OVERHEAD_FIELD, sample.meteringOverheadNanos());
        try {
            return MAPPER.writeValueAsString(row);
        } catch (final JsonProcessingException e) {
            throw new IllegalStateException("Failed to render a raw sample row for " + sample.benchmark(), e);
        }
    }

    /**
     * Parses one row, rejecting a row that is not a JSON object or misses one of the required fields;
     * the two measured metrics must be present explicitly, because a JSON object without them would
     * otherwise be read as a zero sample.
     *
     * @param file the table file, used in the failure messages
     * @param line the row to parse
     * @return the parsed row
     * @throws IllegalStateException if the row does not match the table shape
     */
    private static ColdSample parseRow(final Path file, final String line) {
        final JsonNode row;
        try {
            row = MAPPER.readTree(line);
        } catch (final JsonProcessingException e) {
            throw new IllegalStateException("Raw sampling table " + file + " holds an invalid JSON row: " + line, e);
        }
        if (row == null || !row.isObject()) {
            throw new IllegalStateException("Raw sampling table " + file + " row must be a JSON object: " + line);
        }
        final String benchmark = requiredText(row, BENCHMARK_FIELD, file);
        final String protocol = requiredText(row, PROTOCOL_FIELD, file);
        final Map<String, String> params = readParams(row, file);
        final double nanos = requiredNumber(row, NANOS_FIELD, file).doubleValue();
        final long allocated = requiredNumber(row, ALLOCATED_FIELD, file).longValue();
        final double meteringOverhead = requiredNumber(row, METERING_OVERHEAD_FIELD, file).doubleValue();
        try {
            return new ColdSample(benchmark, params, protocol, nanos, allocated, meteringOverhead);
        } catch (final IllegalArgumentException e) {
            throw new IllegalStateException("Raw sampling table " + file + " holds an unusable row: "
                    + e.getMessage(), e);
        }
    }

    /**
     * Reads a required textual field of one row.
     *
     * @param row   the row
     * @param field the field name
     * @param file  the table file, used in the failure messages
     * @return the textual value
     * @throws IllegalStateException if the field is missing or not textual
     */
    private static String requiredText(final JsonNode row, final String field, final Path file) {
        final JsonNode value = row.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalStateException("Raw sampling table " + file + " row field " + field
                    + " must be a string, got: " + value);
        }
        return value.asText();
    }

    /**
     * Reads a required numeric field of one row.
     *
     * @param row   the row
     * @param field the field name
     * @param file  the table file, used in the failure messages
     * @return the numeric value
     * @throws IllegalStateException if the field is missing or not numeric
     */
    private static JsonNode requiredNumber(final JsonNode row, final String field, final Path file) {
        final JsonNode value = row.get(field);
        if (value == null || !value.isNumber()) {
            throw new IllegalStateException("Raw sampling table " + file + " row field " + field
                    + " must be a number, got: " + value);
        }
        return value;
    }

    /**
     * Reads the parameter binding of one row; a row without a binding carries an empty one.
     *
     * @param row  the row
     * @param file the table file, used in the failure messages
     * @return the parameter binding of the row
     * @throws IllegalStateException if the binding is present but is not an object of strings
     */
    private static Map<String, String> readParams(final JsonNode row, final Path file) {
        final JsonNode params = row.get(PARAMS_FIELD);
        if (params == null || params.isNull()) {
            return Map.of();
        }
        if (!params.isObject()) {
            throw new IllegalStateException("Raw sampling table " + file + " row field " + PARAMS_FIELD
                    + " must be an object, got: " + params);
        }
        final Map<String, String> binding = new LinkedHashMap<>();
        for (final Map.Entry<String, JsonNode> param : params.properties()) {
            if (!param.getValue().isTextual()) {
                throw new IllegalStateException("Raw sampling table " + file + " parameter " + param.getKey()
                        + " must be a string, got: " + param.getValue());
            }
            binding.put(param.getKey(), param.getValue().asText());
        }
        return binding;
    }

    /**
     * One raw sample row of the cold first use protocol: the benchmark it belongs to, its parameter
     * binding, the protocol tag, the time of the target operation, the thread allocated bytes around
     * that operation and the metering overhead measured by the trial.
     *
     * @param benchmark                 fully qualified benchmark name, never blank
     * @param params                    parameter binding of the measured entry, never null
     * @param protocol                  protocol tag of the measured cache state, never blank
     * @param nanosPerOperation         time of the target operation in nanoseconds, positive and finite
     * @param allocatedBytesPerOperation thread allocated bytes around the target operation, not negative
     * @param meteringOverheadNanos     cost of one empty metering sequence in nanoseconds, not negative
     */
    public record ColdSample(String benchmark,
                             Map<String, String> params,
                             String protocol,
                             double nanosPerOperation,
                             long allocatedBytesPerOperation,
                             double meteringOverheadNanos) {

        /**
         * Validates the row and takes an immutable copy of the parameter binding: a blank name or
         * protocol is rejected, because a row that cannot be attributed to a benchmark entry must not
         * enter the table. The completeness of the two measured metrics is enforced by the table entry
         * points, which reject an incomplete row before it is written or handed to a report.
         */
        public ColdSample {
            if (benchmark == null || benchmark.isBlank()) {
                throw new IllegalArgumentException("benchmark name must not be blank, got: " + benchmark);
            }
            if (protocol == null || protocol.isBlank()) {
                throw new IllegalArgumentException("protocol must not be blank, got: " + protocol);
            }
            params = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(params, "params")));
        }
    }
}