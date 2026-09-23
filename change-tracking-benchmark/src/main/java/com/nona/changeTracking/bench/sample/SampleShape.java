package com.nona.changeTracking.bench.sample;

/**
 * Shape of a benchmark sample: scalar field count, nesting depth and collection size.
 * <p>
 * This value object is the frozen parameter descriptor of the sample family: every
 * benchmark task derives its load from it instead of building samples on its own.
 *
 * @param fieldCount     number of scalar fields on the sample root; supported values are
 *                       {@value #SUPPORTED_FIELD_COUNT_LOW} and {@value #SUPPORTED_FIELD_COUNT_HIGH}
 * @param nestingDepth   number of nested address objects below the sample root; at least 1,
 *                       the documented benchmark range is 1 to 5
 * @param collectionSize number of line items held by the sample root; at least 0
 */
public record SampleShape(int fieldCount, int nestingDepth, int collectionSize) {

    /** Narrow sample field count. */
    public static final int SUPPORTED_FIELD_COUNT_LOW = 5;

    /** Wide sample field count. */
    public static final int SUPPORTED_FIELD_COUNT_HIGH = 20;

    /** Field count used when the dimension is not scanned. */
    public static final int DEFAULT_FIELD_COUNT = 20;

    /** Nesting depth used when the dimension is not scanned. */
    public static final int DEFAULT_NESTING_DEPTH = 2;

    /** Collection size used when the dimension is not scanned. */
    public static final int DEFAULT_COLLECTION_SIZE = 100;

    /**
     * Validates every dimension invariant, so no creation path can produce a shape carrying an
     * unsupported dimension; dimensions are checked in the fixed order field count, nesting depth,
     * collection size.
     *
     * @throws IllegalArgumentException if the field count is neither
     *                                  {@value #SUPPORTED_FIELD_COUNT_LOW} nor
     *                                  {@value #SUPPORTED_FIELD_COUNT_HIGH}, if the nesting depth is
     *                                  below 1, or if the collection size is negative
     */
    public SampleShape {
        if (fieldCount != SUPPORTED_FIELD_COUNT_LOW && fieldCount != SUPPORTED_FIELD_COUNT_HIGH) {
            throw new IllegalArgumentException("Unsupported fieldCount: " + fieldCount
                    + ", supported values are " + SUPPORTED_FIELD_COUNT_LOW + " and " + SUPPORTED_FIELD_COUNT_HIGH);
        }
        if (nestingDepth < 1) {
            throw new IllegalArgumentException("nestingDepth must be at least 1, but was " + nestingDepth);
        }
        if (collectionSize < 0) {
            throw new IllegalArgumentException("collectionSize must not be negative, but was " + collectionSize);
        }
    }

    /**
     * Creates a validated shape by delegating to the validating canonical constructor.
     *
     * @param fieldCount     scalar field count, must be {@value #SUPPORTED_FIELD_COUNT_LOW}
     *                       or {@value #SUPPORTED_FIELD_COUNT_HIGH}
     * @param nestingDepth   nesting depth, must be at least 1
     * @param collectionSize collection size, must be at least 0
     * @return the validated shape
     * @throws IllegalArgumentException if any dimension is outside its supported range, thrown by the
     *                                  canonical constructor this factory delegates to
     */
    public static SampleShape of(final int fieldCount, final int nestingDepth, final int collectionSize) {
        return new SampleShape(fieldCount, nestingDepth, collectionSize);
    }

    /**
     * Returns the default shape: {@value #DEFAULT_FIELD_COUNT} scalar fields,
     * depth {@value #DEFAULT_NESTING_DEPTH} and {@value #DEFAULT_COLLECTION_SIZE} items.
     *
     * @return the default shape
     */
    public static SampleShape defaults() {
        return new SampleShape(DEFAULT_FIELD_COUNT, DEFAULT_NESTING_DEPTH, DEFAULT_COLLECTION_SIZE);
    }
}