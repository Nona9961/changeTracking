package com.nona.changeTracking.bench;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Shared fixture of the assembly level integration tests of the cache carriers: it shades the
 * executable benchmark jar once, runs the cold first use protocol once and the steady state protocol
 * once, and exposes the resulting command outcomes to the three test classes.
 * <p>
 * The three integration tests assert different faces of the same real runs, so the shading build and
 * each JMH command run exactly once per test JVM: surefire reuses one JVM across the test classes,
 * and every entry point of this fixture is lazily cached under one lock, so a class running alone and
 * the full run both shade once and run each protocol once.
 * <p>
 * The cold run uses the frozen protocol of {@link FirstUseCacheStateBenchmark} (single shot, zero
 * warmup, one measurement at batch size one, five forks, one thread; the annotations carry the whole
 * protocol, {@code -f 5} states the fork count explicitly) and therefore launches five forks per
 * measured method. The steady run shortens the frozen warmup and measurement windows
 * ({@code -wi 2 -i 3 -w 200ms -r 200ms}) to keep the integration test short, exactly as the
 * {@code FacadeBenchmarkIntegrationTest} does; the shortening weakens none of the asserted criteria,
 * because the entry set is declared by the {@code @Benchmark} methods and the positivity of the time
 * and allocation metrics follows from the measured operation itself.
 * <p>
 * This class declares no test: it is a fixture shared by the {@code *IntegrationTest} classes, so it
 * carries no class level test annotation and no {@code *Test} name suffix.
 */
final class BenchmarkRunFixture {

    /** Module directory, the working directory of the surefire test JVM. */
    static final Path MODULE_DIRECTORY = Path.of("").toAbsolutePath();

    /** Repository root, where the shading build runs. */
    static final Path REPOSITORY_DIRECTORY = MODULE_DIRECTORY.getParent();

    /** Executable benchmark jar produced by the shading build. */
    static final Path BENCHMARK_JAR = MODULE_DIRECTORY.resolve("target").resolve("benchmarks.jar");

    /** Result directory of the cold first use run; isolated from the other integration tests. */
    static final Path COLD_RESULT_DIRECTORY = MODULE_DIRECTORY.resolve("target").resolve("first-use-cache-results");

    /** JMH native result file of the cold first use run. */
    static final Path COLD_RESULT_FILE = COLD_RESULT_DIRECTORY.resolve("jmh-result.json");

    /** Result directory of the steady state run; isolated from the other integration tests. */
    static final Path STEADY_RESULT_DIRECTORY = MODULE_DIRECTORY.resolve("target").resolve("cache-state-results");

    /** JMH native result file of the steady state run. */
    static final Path STEADY_RESULT_FILE = STEADY_RESULT_DIRECTORY.resolve("jmh-result.json");

    /**
     * Result directory of the second steady state run; the pairing check compares the first steady run
     * against this older run by the content derived entry key.
     */
    static final Path STEADY_PAIRING_RESULT_DIRECTORY =
            MODULE_DIRECTORY.resolve("target").resolve("cache-state-pairing-results");

    /** JMH native result file of the second steady state run. */
    static final Path STEADY_PAIRING_RESULT_FILE = STEADY_PAIRING_RESULT_DIRECTORY.resolve("jmh-result.json");

    /**
     * Raw sampling table of the cold protocol; the frozen carrier of
     * {@link FirstUseCacheStateBenchmark#RESULT_DIRECTORY} writes there, the path cannot be chosen by
     * the command line.
     */
    static final Path COLD_SAMPLE_TABLE =
            MODULE_DIRECTORY.resolve("target").resolve("benchmark-results").resolve("cold-samples.jsonl");

    /** Environment record archived by the cold trial assembly. */
    static final Path ENVIRONMENT_RECORD =
            MODULE_DIRECTORY.resolve("target").resolve("benchmark-results").resolve("environment.json");

    /** Cold benchmark selector: exactly the first use protocol carrier. */
    private static final String COLD_BENCHMARK_SELECTOR = "FirstUseCacheStateBenchmark";

    /**
     * Steady state benchmark selector: the three warm cache states and the alternating configuration
     * carrier in one command, as the design freezes for the steady state face.
     * <p>
     * The selector is anchored to the fully qualified class prefix instead of a short class name
     * alternation: JMH matches its benchmark selector as a regular expression
     * search over the fully qualified benchmark name, so the short pattern
     * {@code CacheStateBenchmark|ConfigurationAlternationBenchmark} also matches the substring
     * {@code CacheStateBenchmark} inside {@code FirstUseCacheStateBenchmark} and would run the cold
     * first use protocol a second time (observed: the steady result held the two cold entries beside
     * the five steady entries).
     */
    private static final String STEADY_BENCHMARK_SELECTOR =
            "com\\.nona\\.changeTracking\\.bench\\.CacheStateBenchmark\\."
                    + "|com\\.nona\\.changeTracking\\.bench\\.ConfigurationAlternationBenchmark\\.";

    /** Number of forks the cold protocol samples, the value of {@code FirstUseCacheStateBenchmark.FORKS}. */
    static final int COLD_FORKS = 5;

    /**
     * Shortened steady state protocol options: one fork and shorter warmup and measurement windows
     * than the frozen class level annotations, to keep the integration test short. The shortening
     * weakens none of the asserted criteria, because the entry set is declared by the
     * {@code @Benchmark} methods and the positivity of the time and allocation metrics follows from
     * the measured operation itself.
     */
    private static final List<String> STEADY_PROTOCOL_OPTIONS =
            List.of("-f", "1", "-wi", "2", "-i", "3", "-w", "200ms", "-r", "200ms");

    /** Timeout of the shading build in seconds. */
    private static final long PACKAGE_TIMEOUT_SECONDS = 900;

    /**
     * Timeout of one JMH run in seconds, shared with the other run fixtures of this package; the cold
     * run forks ten JVMs.
     */
    static final long RUN_TIMEOUT_SECONDS = 1_800;

    /** Lock guarding the lazy initialization of the shared command outcomes. */
    private static final Object LOCK = new Object();

    /** Outcome of the shading build, null until the first request. */
    private static ProcessResult packageResult;

    /** Outcome of the cold first use run, null until the first request. */
    private static ProcessResult coldRunResult;

    /** Outcome of the steady state run, null until the first request. */
    private static ProcessResult steadyRunResult;

    /** Outcome of the second steady state run, null until the first request. */
    private static ProcessResult steadyPairingRunResult;

    /**
     * Private constructor: this class is a static fixture.
     */
    private BenchmarkRunFixture() {
    }

    /**
     * Returns the outcome of the shading build, executing it once.
     *
     * @return the outcome of {@code -Pbench package}
     */
    static ProcessResult shadedJar() {
        synchronized (LOCK) {
            if (packageResult == null) {
                packageResult = run(List.of("mvn", "-B", "-o", "-DskipTests", "-pl", "change-tracking-benchmark",
                        "-am", "-Pbench", "package"), REPOSITORY_DIRECTORY, PACKAGE_TIMEOUT_SECONDS);
            }
            return packageResult;
        }
    }

    /**
     * Returns the outcome of the cold first use run, executing the shading build and the run once.
     *
     * @return the outcome of the frozen cold protocol run
     */
    static ProcessResult coldRun() {
        synchronized (LOCK) {
            shadedJar();
            if (coldRunResult == null) {
                createDirectory(COLD_RESULT_DIRECTORY);
                coldRunResult = run(command(COLD_BENCHMARK_SELECTOR, List.of("-f", Integer.toString(COLD_FORKS)),
                        COLD_RESULT_FILE), MODULE_DIRECTORY, RUN_TIMEOUT_SECONDS);
            }
            return coldRunResult;
        }
    }

    /**
     * Returns the outcome of the steady state run, executing the shading build and the run once.
     *
     * @return the outcome of the shortened steady state run
     */
    static ProcessResult steadyRun() {
        synchronized (LOCK) {
            shadedJar();
            if (steadyRunResult == null) {
                createDirectory(STEADY_RESULT_DIRECTORY);
                steadyRunResult = run(command(STEADY_BENCHMARK_SELECTOR, STEADY_PROTOCOL_OPTIONS, STEADY_RESULT_FILE),
                        MODULE_DIRECTORY, RUN_TIMEOUT_SECONDS);
            }
            return steadyRunResult;
        }
    }

    /**
     * Returns the outcome of the second steady state run, executing the shading build and the run once.
     *
     * @return the outcome of the shortened steady state run of the older run
     */
    static ProcessResult steadyPairingRun() {
        synchronized (LOCK) {
            shadedJar();
            if (steadyPairingRunResult == null) {
                createDirectory(STEADY_PAIRING_RESULT_DIRECTORY);
                steadyPairingRunResult = run(
                        command(STEADY_BENCHMARK_SELECTOR, STEADY_PROTOCOL_OPTIONS, STEADY_PAIRING_RESULT_FILE),
                        MODULE_DIRECTORY, RUN_TIMEOUT_SECONDS);
            }
            return steadyPairingRunResult;
        }
    }

    /**
     * Creates the result directory of one run.
     *
     * @param directory the directory to create
     * @throws UncheckedIOException if the directory cannot be created
     */
    static void createDirectory(final Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to create the benchmark result directory " + directory, e);
        }
    }

    /**
     * Builds the command of one JMH run of the shaded jar: the selector, the protocol options of the
     * run and the native result file. The result format and the GC profiler are common to every run,
     * the protocol itself is carried by the class level annotations unless the options state it.
     *
     * @param selector        benchmark selector, a regular expression over fully qualified names
     * @param protocolOptions protocol options inserted after the selector, empty when the class level
     *                        annotations carry the whole protocol
     * @param resultFile      native result file of the run
     * @return the command and its arguments
     */
    static List<String> command(final String selector, final List<String> protocolOptions, final Path resultFile) {
        final List<String> command = new ArrayList<>(List.of("java", "-jar", BENCHMARK_JAR.toString(), selector));
        command.addAll(protocolOptions);
        command.addAll(List.of("-rf", "json", "-prof", "gc", "-rff", resultFile.toString()));
        return command;
    }

    /**
     * Runs an external command in a working directory and reads its merged output back.
     *
     * @param command          the command and its arguments
     * @param workingDirectory the working directory
     * @param timeoutSeconds   the timeout in seconds
     * @return the exit code and merged output of the command
     */
    static ProcessResult run(final List<String> command, final Path workingDirectory, final long timeoutSeconds) {
        final Path logFile;
        try {
            logFile = Files.createTempFile("cache-assembly-integration-", ".log");
            logFile.toFile().deleteOnExit();
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to create the command log file", e);
        }
        final ProcessBuilder builder = new ProcessBuilder(new ArrayList<>(command));
        builder.directory(workingDirectory.toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(logFile.toFile());
        try {
            final Process process = builder.start();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new ProcessResult(-1, "command timed out after " + timeoutSeconds + "s: " + command);
            }
            return new ProcessResult(process.exitValue(), Files.readString(logFile));
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to run " + command, e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running " + command, e);
        }
    }

    /**
     * Returns the values of the output lines carrying the given prefix.
     *
     * @param output the command output
     * @param prefix the line prefix
     * @return the values without their prefix
     */
    static List<String> valuesOf(final String output, final String prefix) {
        return output.lines()
                .filter(line -> line.startsWith(prefix))
                .map(line -> line.substring(prefix.length()))
                .toList();
    }

    /**
     * Returns the tail of an output, for failure messages.
     *
     * @param output the command output
     * @return the last 2000 characters of the output
     */
    static String tail(final String output) {
        final int start = Math.max(0, output.length() - 2_000);
        return output.substring(start);
    }

    /**
     * Outcome of one external command.
     *
     * @param exitCode the exit code, {@code -1} on timeout
     * @param output   the merged standard output and standard error
     */
    record ProcessResult(int exitCode, String output) {
    }
}
