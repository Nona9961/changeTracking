package com.nona.changeTracking.bench.result;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.Objects;

/**
 * Directory of the benchmark module the running classes were loaded from: the parent of the
 * {@code target} directory the classpath location of this class sits in.
 * <p>
 * Inference rule: the classpath location of the module classes is a jar file or a class directory
 * directly below the {@code target} directory of the module ({@code target/benchmarks.jar} of the
 * shaded benchmark jar, {@code target/classes} of a plain build), so the module directory is the
 * parent of that {@code target} directory. The rule is lexical: it derives the directory from the
 * shape of the location and never touches the filesystem, so the caller can use it from any working
 * directory.
 * <p>
 * Prerequisite: this class must have been loaded from the build output of the benchmark module. A
 * classpath location that is not a direct child of a {@code target} directory (a jar the caller copied
 * elsewhere, an unpacked layout, a location without a module directory above it) carries no module
 * directory and is rejected with {@link IllegalStateException} instead of a guessed path.
 * <p>
 * The default options of the archive entry point are resolved with this directory, so an omitted
 * option points into the module the tool was started from and never into the working directory of the
 * call.
 * <p>
 * Package visible: the only consumer is the archive entry point of this package, so the locator stays
 * inside it and is not part of any published surface.
 */
final class BenchmarkModuleDirectory {

    /** Build output directory that holds the classpath location of the module classes. */
    private static final Path TARGET_DIRECTORY = Path.of("target");

    /** Logger of this locator. */
    private static final Logger LOG = LoggerFactory.getLogger(BenchmarkModuleDirectory.class);

    /**
     * Private constructor: this class is a static location entry point.
     */
    private BenchmarkModuleDirectory() {
    }

    /**
     * Locates the benchmark module directory of this class.
     * <p>
     * The classpath location of this class is read from its code source and resolved with
     * {@link #ofClasspathLocation(Path)}.
     *
     * @return the directory of the benchmark module this class was loaded from
     * @throws IllegalStateException if this class carries no code source location, or if the location is not
     *                               a direct child of a {@code target} directory
     */
    static Path resolve() {
        LOG.info("resolve: locating the benchmark module directory of {}", BenchmarkModuleDirectory.class.getName());
        final CodeSource codeSource = BenchmarkModuleDirectory.class.getProtectionDomain().getCodeSource();
        if (codeSource == null) {
            throw new IllegalStateException(withoutCodeSourceLocation());
        }
        final URL location = codeSource.getLocation();
        if (location == null) {
            throw new IllegalStateException(withoutCodeSourceLocation());
        }
        final Path classpathLocation;
        try {
            classpathLocation = Path.of(location.toURI());
        } catch (final URISyntaxException malformedLocation) {
            throw new IllegalStateException("the benchmark module directory cannot be located: the classpath "
                    + "location " + location + " is not a file location", malformedLocation);
        }
        return ofClasspathLocation(classpathLocation);
    }

    /**
     * Derives the benchmark module directory from a classpath location.
     * <p>
     * A location whose parent directory is named {@code target} resolves to the parent of that
     * {@code target} directory; every other location, a relative one included, is rejected with a
     * message carrying the location, so a caller never receives a guessed directory.
     *
     * @param classpathLocation the jar file or class directory the module classes were loaded from, must not be null
     * @return the parent of the {@code target} directory the location sits in
     * @throws NullPointerException  if classpathLocation is null
     * @throws IllegalStateException if the location is not a direct child of a {@code target} directory, or no
     *                               directory sits above that {@code target} directory
     */
    static Path ofClasspathLocation(final Path classpathLocation) {
        LOG.info("ofClasspathLocation: deriving the benchmark module directory from the classpath location {}",
                classpathLocation);
        Objects.requireNonNull(classpathLocation, "classpathLocation");
        final Path targetDirectory = classpathLocation.getParent();
        final Path moduleDirectory = targetDirectory == null ? null : targetDirectory.getParent();
        if (!classpathLocation.isAbsolute() || moduleDirectory == null
                || !TARGET_DIRECTORY.equals(targetDirectory.getFileName())) {
            throw new IllegalStateException(rejectionOf(classpathLocation));
        }
        return moduleDirectory;
    }

    /**
     * Builds the diagnostic of a classpath location that carries no benchmark module directory.
     *
     * @param classpathLocation the classpath location that is not a direct child of a target directory
     * @return the diagnostic carrying the location
     */
    private static String rejectionOf(final Path classpathLocation) {
        return "the benchmark module directory cannot be located from the classpath location "
                + classpathLocation + ": the location is not a direct child of a " + TARGET_DIRECTORY
                + " directory";
    }

    /**
     * Builds the diagnostic of a class without a readable code source location.
     *
     * @return the diagnostic naming this locator class
     */
    private static String withoutCodeSourceLocation() {
        return "the benchmark module directory cannot be located: " + BenchmarkModuleDirectory.class.getName()
                + " carries no code source location";
    }
}