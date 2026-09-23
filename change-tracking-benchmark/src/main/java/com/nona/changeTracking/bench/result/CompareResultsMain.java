package com.nona.changeTracking.bench.result;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Command line entry point of the comparison step: it compares two JMH native result files and
 * writes the difference table of AC3 to standard output.
 * <p>
 * Reporting contract: the rendered difference table ({@link ResultDiff#toTable()}) is written to
 * {@link System#out}. A rejected request, a failing read and a comparison rejected because two values
 * of one metric do not share a unit are written to {@link System#err} and terminate the process with
 * exit code 1; a successful comparison exits with code 0. Operation level SLF4J logging may accompany
 * the two streams, but neither stream depends on a runtime logging backend being present.
 * <p>
 * Usage: {@code --first <result json> --second <result json>}. The two sources may be two
 * consecutive runs of the same version or runs of two different versions. The entry point only
 * parses arguments and delegates to
 * {@link BenchmarkResultComparator#compare(Path, Path)}; it holds no comparison rule.
 */
public final class CompareResultsMain {

    /** Option carrying the first result file. */
    static final String FIRST_OPTION = "--first";

    /** Option carrying the second result file. */
    static final String SECOND_OPTION = "--second";

    /** Exit code of a rejected comparison request. */
    private static final int FAILURE_EXIT_CODE = 1;

    /** Logger of this entry point. */
    private static final Logger LOG = LoggerFactory.getLogger(CompareResultsMain.class);

    /**
     * Private constructor: this class is a command line entry point.
     */
    private CompareResultsMain() {
    }

    /**
     * Compares the two result files and writes the difference table to standard output.
     * <p>
     * Output contract: the arguments are parsed with {@link #parse(String[])}, the request is
     * delegated to {@link BenchmarkResultComparator#compare(Path, Path)} and the full rendered table
     * ({@link ResultDiff#toTable()}) is written to {@link System#out}; the process then exits with code
     * 0. A rejected request, a failing read or a comparison rejected because two values of one metric
     * do not share a unit is written to {@link System#err} and terminates the process with exit code 1.
     * Every invocation produces the table or the diagnostic on the two streams, so the caller never has
     * to enable a logging backend to read the result.
     *
     * @param args the command line arguments
     */
    public static void main(final String[] args) {
        try {
            final Request request = parse(args);
            final ResultDiff diff =
                    BenchmarkResultComparator.compare(request.firstResultFile(), request.secondResultFile());
            LOG.info("Compared {} against {}", request.firstResultFile(), request.secondResultFile());
            System.out.println(diff.toTable());
            System.out.flush();
        } catch (final RuntimeException rejection) {
            LOG.error("Comparison request rejected: {}", rejection.getMessage(), rejection);
            System.err.println("Comparison request rejected: " + rejection.getMessage());
            System.err.flush();
            System.exit(FAILURE_EXIT_CODE);
        }
    }

    /**
     * Parses the comparison command line.
     * <p>
     * Contract implemented in the green phase: both {@code --first} and {@code --second} are
     * mandatory and must carry a non blank path; an option without a value, an unknown option and a
     * missing mandatory option throw {@link IllegalArgumentException}; the order of the options does
     * not matter.
     *
     * @param args the command line arguments, must not be null
     * @return the parsed comparison request
     * @throws NullPointerException     if args is null
     * @throws IllegalArgumentException if a mandatory option is missing or blank, or an option is unknown or without value
     */
    static Request parse(final String[] args) {
        Objects.requireNonNull(args, "args");
        Path firstResultFile = null;
        Path secondResultFile = null;
        for (int index = 0; index < args.length; index++) {
            final String option = args[index];
            switch (option) {
                case FIRST_OPTION -> {
                    firstResultFile = pathOf(args, index);
                    index++;
                }
                case SECOND_OPTION -> {
                    secondResultFile = pathOf(args, index);
                    index++;
                }
                default -> throw new IllegalArgumentException("Unknown option: " + option);
            }
        }
        if (firstResultFile == null) {
            throw new IllegalArgumentException("Missing mandatory option " + FIRST_OPTION);
        }
        if (secondResultFile == null) {
            throw new IllegalArgumentException("Missing mandatory option " + SECOND_OPTION);
        }
        LOG.info("Parsed the comparison command line: first={} second={}", firstResultFile, secondResultFile);
        return new Request(firstResultFile, secondResultFile);
    }

    /**
     * Reads the result file path of one option.
     *
     * @param args  the command line arguments
     * @param index the index of the option
     * @return the path that follows the option
     * @throws IllegalArgumentException if the option carries no value or a blank one
     */
    private static Path pathOf(final String[] args, final int index) {
        if (index + 1 >= args.length) {
            throw new IllegalArgumentException("Option " + args[index] + " requires a value");
        }
        final String value = args[index + 1];
        if (value.isBlank()) {
            throw new IllegalArgumentException("Option " + args[index] + " requires a non blank path");
        }
        return Path.of(value);
    }

    /**
     * Parsed comparison command line.
     *
     * @param firstResultFile  path of the first JMH native result JSON
     * @param secondResultFile path of the second JMH native result JSON
     */
    record Request(Path firstResultFile, Path secondResultFile) {
    }
}