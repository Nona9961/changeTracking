package com.nona.changeTracking.bench.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Command line entry point of the retained memory measurement: it measures the requested retention
 * scenarios on the frozen load and writes their reports to standard output.
 * <p>
 * Usage: {@code [--scenario <name>]}. The option is optional and repeatable; without it every
 * {@link RetainedMemoryScenario} is measured in declaration order. The measurement runs outside any
 * benchmark window, holds each scenario result and reports the footprint that result keeps alive, so
 * the output is a retained memory figure and is neither a time nor an allocation figure.
 * <p>
 * Reporting contract: every report is written to {@link System#out} in the stable line form of
 * {@link RetainedMemoryReport#render()}, reports separated by a blank line. A rejected request or a
 * measurement that cannot read a reference field is written to {@link System#err} and terminates the
 * process with exit code 1; a successful run exits with code 0. Operation level SLF4J logging may
 * accompany the two streams, but neither stream depends on a runtime logging backend being present.
 * <p>
 * The real result graph reaches {@code java.lang} and {@code java.util} types, so the JVM running this
 * entry point has to open those modules, for example
 * {@code java --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED -cp <classpath> com.nona.changeTracking.bench.memory.RetainedMemoryMain}.
 * The entry point only parses arguments and delegates to
 * {@link RetainedMemoryMeasurement#measure(RetainedMemoryScenario)}; it holds no measurement rule.
 */
public final class RetainedMemoryMain {

    /** Option carrying the requested retention scenario, repeatable. */
    static final String SCENARIO_OPTION = "--scenario";

    /** Exit code of a rejected measurement request. */
    private static final int FAILURE_EXIT_CODE = 1;

    /** Logger of this entry point. */
    private static final Logger LOG = LoggerFactory.getLogger(RetainedMemoryMain.class);

    /**
     * Private constructor: this class is a command line entry point.
     */
    private RetainedMemoryMain() {
    }

    /**
     * Measures the requested retention scenarios and writes their reports to standard output.
     * <p>
     * Output contract: the arguments are parsed with {@link #parse(String[])}, every requested
     * scenario is measured with {@link RetainedMemoryMeasurement#measure(RetainedMemoryScenario)} and
     * rendered with {@link RetainedMemoryReport#render()}, and the blocks are written to
     * {@link System#out} separated by a blank line; the process then exits with code 0. A rejected
     * request or a measurement failure is written to {@link System#err} and terminates the process
     * with exit code 1. Every invocation produces the reports or the diagnostic on the two streams, so
     * the caller never has to enable a logging backend to read the result.
     *
     * @param args the command line arguments
     */
    public static void main(final String[] args) {
        try {
            final Request request = parse(args);
            final String output = renderReports(request);
            LOG.info("Retained memory measurement completed for {} scenario(s)", request.scenarios().size());
            System.out.print(output);
            System.out.flush();
        } catch (final RuntimeException failure) {
            System.err.println("Retained memory measurement failed: " + failure.getMessage());
            LOG.error("Retained memory measurement failed", failure);
            System.exit(FAILURE_EXIT_CODE);
        }
    }

    /**
     * Measures every requested scenario and renders their reports separated by a blank line.
     *
     * @param request the parsed measurement request
     * @return the rendered report blocks
     */
    private static String renderReports(final Request request) {
        final StringBuilder output = new StringBuilder();
        for (int index = 0; index < request.scenarios().size(); index++) {
            if (index > 0) {
                output.append(System.lineSeparator());
            }
            final RetainedMemoryReport report = RetainedMemoryMeasurement.measure(request.scenarios().get(index));
            for (final String line : report.render()) {
                output.append(line).append(System.lineSeparator());
            }
        }
        return output.toString();
    }

    /**
     * Parses the measurement command line.
     * <p>
     * {@code --scenario} is optional and repeatable and must carry the stable token of a scenario; an
     * omitted option yields every scenario in declaration order. An option without a value and an
     * unknown option throw {@link IllegalArgumentException}; the order of the options does not matter.
     *
     * @param args the command line arguments, must not be null
     * @return the parsed measurement request
     * @throws NullPointerException     if args is null
     * @throws IllegalArgumentException if an option is unknown or carries no value, or a scenario token is unknown
     */
    static Request parse(final String[] args) {
        Objects.requireNonNull(args, "args");
        final List<RetainedMemoryScenario> scenarios = new ArrayList<>();
        for (int index = 0; index < args.length; index++) {
            final String argument = args[index];
            if (!SCENARIO_OPTION.equals(argument)) {
                throw new IllegalArgumentException("Unknown option: " + argument);
            }
            if (index + 1 >= args.length) {
                throw new IllegalArgumentException("Option " + SCENARIO_OPTION + " requires a scenario value");
            }
            scenarios.add(RetainedMemoryScenario.fromCommandLineName(args[index + 1]));
            index++;
        }
        if (scenarios.isEmpty()) {
            return new Request(List.of(RetainedMemoryScenario.values()));
        }
        return new Request(List.copyOf(scenarios));
    }

    /**
     * Parsed measurement command line.
     *
     * @param scenarios the requested retention scenarios, in request order
     */
    record Request(List<RetainedMemoryScenario> scenarios) {

        /**
         * Verifies that the parsed request carries a scenario list.
         *
         * @param scenarios the requested retention scenarios, in request order
         */
        Request {
            Objects.requireNonNull(scenarios, "scenarios");
        }
    }
}
