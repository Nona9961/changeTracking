package com.nona.changeTracking.bench.result;

/**
 * Significance verdict of one metric delta, expressed as an explicit label instead of a boolean
 * flag.
 * <p>
 * The difference of two runs is marked as not significant while it stays inside the
 * reported error; an explicit label keeps the difference between "no verdict yet" and "checked and
 * not significant" out of the model.
 */
public enum Significance {

    /** The delta exceeds the reported error of the two results. */
    SIGNIFICANT,

    /** The delta is zero or stays inside the reported error of the two results. */
    INSIGNIFICANT
}