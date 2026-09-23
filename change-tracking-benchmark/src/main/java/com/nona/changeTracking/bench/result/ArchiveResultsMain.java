package com.nona.changeTracking.bench.result;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Command line entry point of the archive step: it archives the artefacts of the last run under the
 * directory name derived from the caller supplied identifier and the instant of the archival.
 * <p>
 * Usage: {@code [--id <identifier>] [--source <run output directory>] [--root <archive root>]}.
 * {@code --id} is optional: the caller decides which identifier identifies the run (a version, or a
 * short commit id it looked up itself) and omits the option when it has none; this tool never runs
 * git or any other external process and never guesses an identifier from the environment. Defaults:
 * {@code --source} is {@value BenchmarkResultArchiver#DEFAULT_SOURCE_DIRECTORY} and {@code --root} is
 * {@value BenchmarkResultArchiver#DEFAULT_RESULTS_ROOT}, both relative to the benchmark module
 * directory. The entry point only parses arguments, supplies the current instant and delegates to
 * {@link BenchmarkResultArchiver#archive(Optional, Path, Path, Instant)}; it holds no archiving rule.
 * <p>
 * Reporting contract: the path of the created archive directory is written to {@link System#out}. A
 * rejected request and a failing copy are written to {@link System#err} and terminate the process with
 * exit code 1; a successful archival exits with code 0. Operation level SLF4J logging may accompany
 * the two streams, but neither stream depends on a runtime logging backend being present.
 */
public final class ArchiveResultsMain {

    /** Option carrying the caller supplied run identifier. */
    static final String ID_OPTION = "--id";

    /** Option overriding the run output directory. */
    static final String SOURCE_OPTION = "--source";

    /** Option overriding the archive root. */
    static final String ROOT_OPTION = "--root";

    /** Exit code of a rejected archive request. */
    private static final int FAILURE_EXIT_CODE = 1;

    /** Logger of this entry point. */
    private static final Logger LOG = LoggerFactory.getLogger(ArchiveResultsMain.class);

    /**
     * Private constructor: this class is a command line entry point.
     */
    private ArchiveResultsMain() {
    }

    /**
     * Archives the run artefacts and writes the archive directory to standard output.
     * <p>
     * Output contract: the arguments are parsed with {@link #parse(String[])}, the request is delegated
     * to {@link BenchmarkResultArchiver#archive(Optional, Path, Path, Instant)} with the current
     * instant, and the path of the resulting archive directory is written to {@link System#out}; the
     * process then exits with code 0. A rejected request or a failing copy is written to
     * {@link System#err} and terminates the process with exit code 1. Every invocation produces the
     * archive directory or the diagnostic on the two streams, so the caller never has to enable a
     * logging backend to read the result.
     *
     * @param args the command line arguments
     */
    public static void main(final String[] args) {
        try {
            final Request request = parse(args);
            final Path archiveDirectory = BenchmarkResultArchiver.archive(
                    request.runId(), request.sourceDirectory(), request.resultsRoot(), Instant.now());
            LOG.info("Archived the benchmark run into {}", archiveDirectory);
            System.out.println(archiveDirectory);
            System.out.flush();
        } catch (final RuntimeException rejection) {
            LOG.error("Archive request rejected: {}", rejection.getMessage(), rejection);
            System.err.println("Archive request rejected: " + rejection.getMessage());
            System.err.flush();
            System.exit(FAILURE_EXIT_CODE);
        }
    }

    /**
     * Parses the archive command line.
     * <p>
     * {@code --id} is optional and, when given, is validated
     * by {@link BenchmarkRunId}; without it the parsed request carries
     * {@code Optional.empty()} as its identifier. {@code --source} and {@code --root} fall back to the
     * module relative defaults; an option without a value, an unknown option and an unusable
     * identifier throw {@link IllegalArgumentException}; the order of the options does not matter.
     *
     * @param args the command line arguments, must not be null
     * @return the parsed archive request
     * @throws NullPointerException     if args is null
     * @throws IllegalArgumentException if the identifier is unusable, or an option is unknown or without value
     */
    static Request parse(final String[] args) {
        Objects.requireNonNull(args, "args");
        Optional<BenchmarkRunId> runId = Optional.empty();
        Path sourceDirectory = BenchmarkResultArchiver.DEFAULT_SOURCE_DIRECTORY;
        Path resultsRoot = BenchmarkResultArchiver.DEFAULT_RESULTS_ROOT;
        for (int index = 0; index < args.length; index++) {
            final String option = args[index];
            switch (option) {
                case ID_OPTION -> {
                    runId = Optional.of(new BenchmarkRunId(valueOf(args, index)));
                    index++;
                }
                case SOURCE_OPTION -> {
                    sourceDirectory = Path.of(valueOf(args, index));
                    index++;
                }
                case ROOT_OPTION -> {
                    resultsRoot = Path.of(valueOf(args, index));
                    index++;
                }
                default -> throw new IllegalArgumentException("Unknown option: " + option);
            }
        }
        LOG.info("Parsed the archive command line: id={} source={} root={}",
                runId.map(BenchmarkRunId::value).orElse("none"), sourceDirectory, resultsRoot);
        return new Request(runId, sourceDirectory, resultsRoot);
    }

    /**
     * Reads the value of one option.
     *
     * @param args  the command line arguments
     * @param index the index of the option
     * @return the value that follows the option
     * @throws IllegalArgumentException if the option carries no value
     */
    private static String valueOf(final String[] args, final int index) {
        if (index + 1 >= args.length) {
            throw new IllegalArgumentException("Option " + args[index] + " requires a value");
        }
        return args[index + 1];
    }

    /**
     * Parsed archive command line.
     *
     * @param runId           caller supplied run identifier, empty when the caller supplied none
     * @param sourceDirectory directory holding the artefacts of the run
     * @param resultsRoot     archive root the run is archived below
     */
    record Request(Optional<BenchmarkRunId> runId, Path sourceDirectory, Path resultsRoot) {
    }
}