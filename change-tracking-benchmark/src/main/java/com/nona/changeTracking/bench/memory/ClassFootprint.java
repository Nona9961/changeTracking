package com.nona.changeTracking.bench.memory;

import java.util.Objects;

/**
 * Retained footprint of one object class inside a measured result graph: how many instances of the
 * class the held result root keeps alive and how many bytes those instances occupy.
 * <p>
 * The dominant retained objects of a report are these records aggregated per class and ordered by
 * descending retained bytes, so a reviewer can see which classes the held result actually holds.
 *
 * @param className   binary name of the object class, never blank
 * @param objectCount number of reachable instances of the class, at least 1
 * @param bytes       summed shallow size of those instances, not negative
 */
public record ClassFootprint(String className, int objectCount, long bytes) {

    /**
     * Validates the class footprint: the class name must be present and the count and byte figures
     * must be positive respectively not negative.
     *
     * @param className   binary name of the object class, never blank
     * @param objectCount number of reachable instances of the class, at least 1
     * @param bytes       summed shallow size of those instances, not negative
     * @throws IllegalArgumentException if the class name is blank, the count is below 1 or the bytes are negative
     */
    public ClassFootprint {
        Objects.requireNonNull(className, "className");
        if (className.isBlank()) {
            throw new IllegalArgumentException("className must not be blank");
        }
        if (objectCount < 1) {
            throw new IllegalArgumentException("objectCount must be at least 1, but was " + objectCount);
        }
        if (bytes < 0) {
            throw new IllegalArgumentException("bytes must not be negative, but was " + bytes);
        }
    }
}
