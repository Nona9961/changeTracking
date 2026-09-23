package com.nona.changeTracking.bench.result;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reads a JMH native result JSON into result entries.
 * <p>
 * The reader is the single place that knows the JMH JSON shape: the root is an array, every element
 * carries {@code benchmark}, an optional {@code params} object, {@code primaryMetric} and the
 * {@code gc.alloc.rate.norm} secondary metric. Parsing is delegated to Jackson, so the archived
 * result is read with a maintained JSON parser instead of a hand written one. Only the fields the
 * archive and the comparison need are projected.
 */
public final class JmhResultJsonParser {

    /** File name of the JMH native result written by the benchmark command. */
    public static final String RESULT_FILE_NAME = "jmh-result.json";

    /** Name of the allocation profiler metric inside the JMH {@code secondaryMetrics} object. */
    private static final String ALLOCATION_METRIC_FIELD = "gc.alloc.rate.norm";

    /** Field name of the fully qualified benchmark of one JMH result entry. */
    private static final String BENCHMARK_FIELD = "benchmark";

    /** Field name of the parameter binding of one JMH result entry. */
    private static final String PARAMS_FIELD = "params";

    /** Name of the primary metric of one JMH result entry. */
    private static final String PRIMARY_METRIC_FIELD = "primaryMetric";

    /** Field name of the secondary metrics of one JMH result entry. */
    private static final String SECONDARY_METRICS_FIELD = "secondaryMetrics";

    /** Field name of the score of one metric. */
    private static final String SCORE_FIELD = "score";

    /** Field name of the reported error of one score. */
    private static final String SCORE_ERROR_FIELD = "scoreError";

    /** Field name of the unit of one score. */
    private static final String SCORE_UNIT_FIELD = "scoreUnit";

    /** Jackson reader of the JMH result document. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Logger of this reader. */
    private static final Logger LOG = LoggerFactory.getLogger(JmhResultJsonParser.class);

    /**
     * Private constructor: this class is a static reading entry point.
     */
    private JmhResultJsonParser() {
    }

    /**
     * Parses the given JMH native result file.
     * <p>
     * Contract implemented in the green phase: an empty root array yields an empty list instead of
     * failing; a root that is not an array, a missing or blank {@code benchmark}, a missing
     * {@code primaryMetric}, a missing or blank {@code score} or {@code scoreUnit}, a {@code score}
     * that is not a number, a missing {@code secondaryMetrics.gc.alloc.rate.norm} (a run without the
     * allocation profiler) and malformed JSON throw {@link IllegalStateException} with the offending
     * position in the message; a missing {@code scoreError} is read as the explicit zero value; a
     * missing {@code params} object yields an empty parameter binding; a file that cannot be read
     * (missing file, path that is a directory) throws {@link java.io.UncheckedIOException}.
     * <p>
     * Every deviation from the shape is presented as {@link IllegalStateException}, including the
     * inputs rejected by the entry and metric value objects, so a malformed result has one failure
     * type for the caller.
     *
     * @param resultFile path of the JMH native result JSON, must not be null
     * @return the parsed entries, in file order
     * @throws NullPointerException         if resultFile is null
     * @throws IllegalStateException        if the file content does not match the JMH result shape
     * @throws java.io.UncheckedIOException if the file cannot be read
     */
    public static List<BenchmarkResultEntry> parse(final Path resultFile) {
        Objects.requireNonNull(resultFile, "resultFile");
        LOG.info("Reading the JMH result file {}", resultFile);
        final JsonNode root = readDocument(resultFile);
        if (!root.isArray()) {
            throw new IllegalStateException(resultFile + ": the JMH result root must be an array, got "
                    + root.getNodeType());
        }
        final List<BenchmarkResultEntry> entries = new ArrayList<>();
        for (int index = 0; index < root.size(); index++) {
            entries.add(readEntry(root.get(index), index, resultFile));
        }
        return List.copyOf(entries);
    }

    /**
     * Reads the JSON document of a JMH result file.
     *
     * @param resultFile path of the result file
     * @return the parsed document
     * @throws IllegalStateException        if the file is empty or not valid JSON
     * @throws java.io.UncheckedIOException if the file cannot be read
     */
    private static JsonNode readDocument(final Path resultFile) {
        final String content;
        try {
            content = Files.readString(resultFile);
        } catch (final IOException failure) {
            throw new UncheckedIOException("Failed to read the JMH result file " + resultFile, failure);
        }
        final JsonNode root;
        try {
            root = MAPPER.readTree(content);
        } catch (final JsonProcessingException failure) {
            throw new IllegalStateException(resultFile + ": the JMH result is not valid JSON", failure);
        }
        if (root == null || root.isMissingNode()) {
            throw new IllegalStateException(resultFile + ": the JMH result document is empty");
        }
        return root;
    }

    /**
     * Reads one entry of the result array.
     *
     * @param element    the JSON element of the entry
     * @param index      the position of the entry in the root array
     * @param resultFile path of the result file, used in the failure messages
     * @return the parsed entry
     * @throws IllegalStateException if the element does not match the JMH result entry shape
     */
    private static BenchmarkResultEntry readEntry(final JsonNode element, final int index, final Path resultFile) {
        final String position = resultFile + ": entry " + index;
        if (!element.isObject()) {
            throw new IllegalStateException(position + " must be a JSON object, got " + element.getNodeType());
        }
        final String benchmark = readBenchmark(element, position);
        final Map<String, String> params = readParams(element, position);
        final MetricValue primaryMetric = readMetric(element.get(PRIMARY_METRIC_FIELD), PRIMARY_METRIC_FIELD, position);
        final JsonNode secondaryMetrics = element.get(SECONDARY_METRICS_FIELD);
        final JsonNode allocationNode = secondaryMetrics == null ? null : secondaryMetrics.get(ALLOCATION_METRIC_FIELD);
        final MetricValue allocationMetric = readMetric(allocationNode, ALLOCATION_METRIC_FIELD, position);
        return new BenchmarkResultEntry(benchmark, params, primaryMetric, allocationMetric);
    }

    /**
     * Reads the fully qualified benchmark name of one entry.
     *
     * @param element  the JSON element of the entry
     * @param position the position of the entry, used in the failure messages
     * @return the benchmark name
     * @throws IllegalStateException if the name is missing, not textual or blank
     */
    private static String readBenchmark(final JsonNode element, final String position) {
        final JsonNode benchmark = element.get(BENCHMARK_FIELD);
        if (benchmark == null || !benchmark.isTextual() || benchmark.asText().isBlank()) {
            throw new IllegalStateException(
                    position + ": field " + BENCHMARK_FIELD + " must be a non blank string, got " + benchmark);
        }
        return benchmark.asText();
    }

    /**
     * Reads the parameter binding of one entry.
     *
     * @param element  the JSON element of the entry
     * @param position the position of the entry, used in the failure messages
     * @return the parameter binding, empty when the entry carries none
     * @throws IllegalStateException if the binding is present but is not an object of strings
     */
    private static Map<String, String> readParams(final JsonNode element, final String position) {
        final JsonNode params = element.get(PARAMS_FIELD);
        if (params == null || params.isNull()) {
            return Map.of();
        }
        if (!params.isObject()) {
            throw new IllegalStateException(
                    position + ": field " + PARAMS_FIELD + " must be a JSON object, got " + params.getNodeType());
        }
        final Map<String, String> binding = new LinkedHashMap<>();
        for (final Map.Entry<String, JsonNode> param : params.properties()) {
            if (!param.getValue().isTextual()) {
                throw new IllegalStateException(position + ": parameter " + param.getKey()
                        + " must be a string, got " + param.getValue().getNodeType());
            }
            binding.put(param.getKey(), param.getValue().asText());
        }
        return binding;
    }

    /**
     * Reads one metric of one entry.
     *
     * @param node       the JSON node of the metric, null when the metric is missing
     * @param metricName name of the metric, used in the failure messages
     * @param position   the position of the entry, used in the failure messages
     * @return the metric value
     * @throws IllegalStateException if the metric, its score or its unit are missing or unusable
     */
    private static MetricValue readMetric(final JsonNode node, final String metricName, final String position) {
        if (node == null || !node.isObject()) {
            throw new IllegalStateException(position + ": metric " + metricName + " is missing");
        }
        final JsonNode score = node.get(SCORE_FIELD);
        if (score == null || !score.isNumber()) {
            throw new IllegalStateException(position + ": metric " + metricName + " field " + SCORE_FIELD
                    + " must be a number, got " + score);
        }
        final JsonNode unit = node.get(SCORE_UNIT_FIELD);
        if (unit == null || !unit.isTextual() || unit.asText().isBlank()) {
            throw new IllegalStateException(position + ": metric " + metricName + " field " + SCORE_UNIT_FIELD
                    + " must be a non blank string, got " + unit);
        }
        final double scoreError = readScoreError(node, metricName, position);
        try {
            return new MetricValue(score.asDouble(), scoreError, unit.asText());
        } catch (final IllegalArgumentException rejected) {
            throw new IllegalStateException(position + ": metric " + metricName + " is unusable: "
                    + rejected.getMessage(), rejected);
        }
    }

    /**
     * Reads the reported error of one metric, absent in a result without reported error.
     *
     * @param node       the JSON node of the metric
     * @param metricName name of the metric, used in the failure messages
     * @param position   the position of the entry, used in the failure messages
     * @return the reported error, {@code 0} when the metric carries none
     * @throws IllegalStateException if the error is present but is not a number
     */
    private static double readScoreError(final JsonNode node, final String metricName, final String position) {
        final JsonNode scoreError = node.get(SCORE_ERROR_FIELD);
        if (scoreError == null || scoreError.isNull()) {
            return 0.0;
        }
        if (!scoreError.isNumber()) {
            throw new IllegalStateException(position + ": metric " + metricName + " field " + SCORE_ERROR_FIELD
                    + " must be a number, got " + scoreError);
        }
        return scoreError.asDouble();
    }
}