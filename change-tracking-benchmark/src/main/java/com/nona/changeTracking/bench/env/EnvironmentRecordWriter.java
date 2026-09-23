package com.nona.changeTracking.bench.env;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Writes the environment record next to the benchmark result JSON.
 * <p>
 * The target directory is created when missing and an existing record file is overwritten,
 * so a repeated run of the same command leaves exactly one record. A failed write leaves no
 * partial file behind and surfaces as {@link java.io.UncheckedIOException}.
 */
public final class EnvironmentRecordWriter {

    /** File name of the archived environment record. */
    public static final String FILE_NAME = "environment.json";

    /**
     * Private constructor: this class is a static write entry point.
     */
    private EnvironmentRecordWriter() {
    }

    /**
     * Writes the given record into the output directory.
     *
     * @param record          the environment record to archive, must not be null
     * @param outputDirectory directory receiving {@value #FILE_NAME}, created when missing
     * @return the path of the written record file
     * @throws NullPointerException   if record or outputDirectory is null
     * @throws java.io.UncheckedIOException if the directory cannot be created or the file cannot be written
     */
    public static Path write(final EnvironmentRecord record, final Path outputDirectory) {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        final Path target = outputDirectory.resolve(FILE_NAME);
        try {
            Files.createDirectories(outputDirectory);
            Files.writeString(target, record.toJson());
            return target;
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to write the environment record to " + target, e);
        }
    }
}