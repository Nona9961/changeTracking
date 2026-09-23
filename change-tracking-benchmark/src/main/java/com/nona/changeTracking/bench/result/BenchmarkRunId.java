package com.nona.changeTracking.bench.result;

/**
 * Run identifier supplied by the caller for one archived benchmark run (a version, or a short commit
 * id the caller looked up itself).
 * <p>
 * The identifier becomes the leading part of an archive directory name, so it is modelled as a path
 * safe value object: the caller supplies one identifier and the directory name is derived from it.
 * Supplying none is a legitimate case and is expressed by the entry point as
 * {@code Optional.empty()}, never as a null or a blank value; nothing is guessed from the environment,
 * and the tool runs no git or other external process to obtain it.
 *
 * @param value the caller supplied run identifier, preserved verbatim
 */
public record BenchmarkRunId(String value) {

    /**
     * Validates the run identifier.
     * <p>
     * A {@code null} value, a blank value, a value with
     * surrounding whitespace, a value equal to {@code .}, a value containing {@code /}, {@code \}
     * or the {@code ..} traversal sequence throws {@link IllegalArgumentException}; every other
     * non blank value is kept verbatim (no trimming, no rewriting).
     *
     * @param value the caller supplied run identifier
     */
    public BenchmarkRunId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("run identifier must not be blank, got: " + value);
        }
        if (!value.equals(value.strip())) {
            throw new IllegalArgumentException("run identifier must not carry surrounding whitespace, got: " + value);
        }
        if (value.contains("/") || value.contains("\\")) {
            throw new IllegalArgumentException("run identifier must not contain a path separator, got: " + value);
        }
        if (value.contains("..") || ".".equals(value)) {
            throw new IllegalArgumentException("run identifier must not be a traversal path segment, got: " + value);
        }
    }
}