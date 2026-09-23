package com.nona.changeTracking.bench.result;

import com.nona.changeTracking.bench.env.EnvironmentRecordWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Archives the artefacts of one benchmark run below the archive root.
 * <p>
 * A run produces two artefacts in the same output directory: the JMH native result
 * ({@value JmhResultJsonParser#RESULT_FILE_NAME}) and the environment record
 * ({@link com.nona.changeTracking.bench.env.EnvironmentRecordWriter#FILE_NAME}). Archiving copies
 * both into {@code <resultsRoot>/<archive directory name>}, so one directory holds the numbers and the
 * environment they were taken in. The directory name is derived by
 * {@link ArchiveDirectoryName#of(Optional, Instant)} from the caller supplied identifier and the
 * instant of the archival; a name already taken gains a sequence number (see
 * {@link #resolveArchiveDirectory(Path, ArchiveDirectoryName)}), so archiving the same identifier and
 * second twice never fails and never touches an existing archive.
 * <p>
 * The archive root is resolved relative to the benchmark module directory
 * ({@value #DEFAULT_RESULTS_ROOT}); the identifier is supplied by the caller and is never guessed
 * from the environment (this class runs no external process).
 */
public final class BenchmarkResultArchiver {

    /** Archive root below the benchmark module directory. */
    public static final Path DEFAULT_RESULTS_ROOT = Path.of("benchmark", "results");

    /** Run output directory produced by the benchmark command. */
    public static final Path DEFAULT_SOURCE_DIRECTORY = Path.of("target", "benchmark-results");

    /**
     * Upper bound of the conflict attempts for one archive directory name: the base name plus the
     * sequence numbers {@code 2} to this value. A further conflict is an error, never an overwrite.
     */
    public static final int MAX_CONFLICT_ATTEMPTS = 100;

    /** Logger of this archiver. */
    private static final Logger LOG = LoggerFactory.getLogger(BenchmarkResultArchiver.class);

    /**
     * Private constructor: this class is a static archiving entry point.
     */
    private BenchmarkResultArchiver() {
    }

    /**
     * Resolves the archive root against the benchmark module directory.
     *
     * @param moduleDirectory the benchmark module directory, must not be null
     * @return {@code moduleDirectory/<}{@value #DEFAULT_RESULTS_ROOT}{@code >}
     * @throws NullPointerException if moduleDirectory is null
     */
    public static Path resolveResultsRoot(final Path moduleDirectory) {
        Objects.requireNonNull(moduleDirectory, "moduleDirectory");
        return moduleDirectory.resolve(DEFAULT_RESULTS_ROOT);
    }

    /**
     * Resolves the archive directory of one archival and creates it.
     * <p>
     * Contract implemented in the green phase: the candidate names are {@code baseName} followed by
     * {@code baseName.withSequence(2)} up to {@code withSequence(}{@value #MAX_CONFLICT_ATTEMPTS}{@code )};
     * each candidate is created with a single atomic directory creation, so a name that appears
     * concurrently is detected instead of being overwritten. An existing directory or regular file of
     * that name counts as taken and moves the archival to the next candidate; an existing entry is
     * never overwritten, renamed or deleted. Every candidate taken throws
     * {@link IllegalStateException}. Any other I/O failure (missing root, path blocked by a file,
     * missing permission) surfaces as {@link java.io.UncheckedIOException} instead of being retried as
     * a conflict.
     *
     * @param resultsRoot the archive root, must already exist
     * @param baseName    the name derived from the run identifier and the archival instant
     * @return the created archive directory of this archival
     * @throws NullPointerException         if an argument is null
     * @throws IllegalStateException        if every candidate name up to the attempt limit is taken
     * @throws java.io.UncheckedIOException if the archive directory cannot be created
     */
    public static Path resolveArchiveDirectory(final Path resultsRoot, final ArchiveDirectoryName baseName) {
        Objects.requireNonNull(resultsRoot, "resultsRoot");
        Objects.requireNonNull(baseName, "baseName");
        for (int attempt = 1; attempt <= MAX_CONFLICT_ATTEMPTS; attempt++) {
            final ArchiveDirectoryName candidate = attempt == 1 ? baseName : baseName.withSequence(attempt);
            final Path candidateDirectory = resultsRoot.resolve(candidate.name());
            try {
                Files.createDirectory(candidateDirectory);
                return candidateDirectory;
            } catch (final FileAlreadyExistsException taken) {
                LOG.info("Archive directory {} is taken by an existing entry, trying the next name",
                        candidateDirectory);
            } catch (final IOException failure) {
                throw new UncheckedIOException("Failed to create the archive directory " + candidateDirectory,
                        failure);
            }
        }
        throw new IllegalStateException("Every candidate name of " + baseName.name() + " up to "
                + MAX_CONFLICT_ATTEMPTS + " attempts is taken below " + resultsRoot);
    }

    /**
     * Archives the two run artefacts under a freshly resolved directory of the archive root.
     * <p>
     * Contract implemented in the green phase, in this order:
     * <ol>
     *   <li>the source directory must exist and must be a directory, otherwise
     *       {@link IllegalStateException};</li>
     *   <li>both artefacts must exist as regular files, otherwise {@link IllegalStateException}
     *       (an incomplete run is never archived);</li>
     *   <li>the archive root is created when missing, then the archive directory of this archival is
     *       resolved with {@link #resolveArchiveDirectory(Path, ArchiveDirectoryName)}: an existing
     *       directory name of an earlier run moves this archival to the next sequence number and is
     *       never overwritten, renamed or deleted;</li>
     *   <li>both artefacts are copied into the resolved directory and it is returned.</li>
     * </ol>
     * A request rejected in the first two steps creates nothing, so the archive root gains no entry. A
     * copy that fails with an I/O error surfaces as {@link java.io.UncheckedIOException}; the
     * directory created by this archival is then left in place (an archive is never deleted by
     * guesswork) and the caller reports the incomplete archive.
     *
     * @param runId           caller supplied identifier, {@code Optional.empty()} when the caller
     *                        supplied none (the directory name is then the timestamp alone)
     * @param sourceDirectory directory holding both run artefacts, must not be null
     * @param resultsRoot     archive root, created when missing
     * @param timestamp       instant of the archival, rendered into the directory name
     * @return the archive directory holding both artefacts
     * @throws NullPointerException         if an argument is null
     * @throws IllegalStateException        if the source artefacts are missing or every candidate directory name is taken
     * @throws java.io.UncheckedIOException if the archive root or directory cannot be created, or an artefact cannot be copied
     */
    public static Path archive(final Optional<BenchmarkRunId> runId,
                               final Path sourceDirectory,
                               final Path resultsRoot,
                               final Instant timestamp) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(sourceDirectory, "sourceDirectory");
        Objects.requireNonNull(resultsRoot, "resultsRoot");
        Objects.requireNonNull(timestamp, "timestamp");
        final Path resultFile = sourceDirectory.resolve(JmhResultJsonParser.RESULT_FILE_NAME);
        final Path environmentFile = sourceDirectory.resolve(EnvironmentRecordWriter.FILE_NAME);
        if (!Files.isDirectory(sourceDirectory)) {
            throw new IllegalStateException("The source directory of the benchmark run is missing: "
                    + sourceDirectory);
        }
        if (!Files.isRegularFile(resultFile)) {
            throw new IllegalStateException("The benchmark run artefact is missing: " + resultFile);
        }
        if (!Files.isRegularFile(environmentFile)) {
            throw new IllegalStateException("The benchmark run artefact is missing: " + environmentFile);
        }
        try {
            Files.createDirectories(resultsRoot);
        } catch (final IOException failure) {
            throw new UncheckedIOException("Failed to create the archive root " + resultsRoot, failure);
        }
        final Path archiveDirectory =
                resolveArchiveDirectory(resultsRoot, ArchiveDirectoryName.of(runId, timestamp));
        copyArtefact(resultFile, archiveDirectory.resolve(JmhResultJsonParser.RESULT_FILE_NAME));
        copyArtefact(environmentFile, archiveDirectory.resolve(EnvironmentRecordWriter.FILE_NAME));
        LOG.info("Archived the artefacts of {} into {}", sourceDirectory, archiveDirectory);
        return archiveDirectory;
    }

    /**
     * Copies one run artefact into the archive directory.
     *
     * @param source the artefact of the run
     * @param target the target path inside the archive directory
     * @throws java.io.UncheckedIOException if the artefact cannot be copied
     */
    private static void copyArtefact(final Path source, final Path target) {
        try {
            Files.copy(source, target);
        } catch (final IOException failure) {
            throw new UncheckedIOException("Failed to copy " + source + " into the archive " + target, failure);
        }
    }
}