package com.nona.changeTracking.bench.memory;

import java.util.List;
import java.util.Objects;

/**
 * Retained footprint of one held result: the total shallow size and object count of every object the
 * held result root keeps reachable, with the per class aggregation of the same reachable set.
 * <p>
 * This is a value object of the report: it carries the measured numbers only and no reference to the
 * traversed objects. The class footprints are ordered by descending retained bytes (ties by
 * descending object count, then by class name) so the dominant retained objects of a report are the
 * leading entries and two runs are comparable line by line.
 *
 * @param retainedBytes   total shallow size of the reachable objects, not negative
 * @param objectCount     number of distinct reachable objects, not negative
 * @param classFootprints per class aggregation of the reachable objects, never null
 */
public record RetainedFootprint(long retainedBytes, int objectCount, List<ClassFootprint> classFootprints) {

    /**
     * Validates the footprint, defensively copies the class aggregation and keeps it read only.
     *
     * @param retainedBytes   total shallow size of the reachable objects, not negative
     * @param objectCount     number of distinct reachable objects, not negative
     * @param classFootprints per class aggregation of the reachable objects, never null
     * @throws NullPointerException     if the class aggregation is null or holds a null element
     * @throws IllegalArgumentException if a byte or object count is negative
     */
    public RetainedFootprint {
        if (retainedBytes < 0) {
            throw new IllegalArgumentException("retainedBytes must not be negative, but was " + retainedBytes);
        }
        if (objectCount < 0) {
            throw new IllegalArgumentException("objectCount must not be negative, but was " + objectCount);
        }
        Objects.requireNonNull(classFootprints, "classFootprints");
        classFootprints = List.copyOf(classFootprints);
    }

    /**
     * Returns at most the given number of the leading class footprints, that is the dominant retained
     * objects of this footprint, in the order this record keeps them.
     *
     * @param limit the maximum number of class footprints to return, at least 0
     * @return the leading class footprints, the full list when the limit exceeds its size
     * @throws IllegalArgumentException if the limit is negative
     */
    public List<ClassFootprint> dominantClasses(final int limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("limit must not be negative, but was " + limit);
        }
        if (limit >= classFootprints.size()) {
            return classFootprints;
        }
        return classFootprints.subList(0, limit);
    }
}
