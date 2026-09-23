package com.nona.changeTracking.bench.env;

/**
 * Machine identity facts captured with every benchmark run.
 *
 * @param hostName            host name, or {@value #UNKNOWN_HOST_NAME} when the host cannot be resolved
 * @param availableProcessors processors available to the benchmark JVM, at least 1
 * @param maxMemoryBytes      maximum heap size of the benchmark JVM in bytes, at least 1
 */
public record MachineIdentity(String hostName, int availableProcessors, long maxMemoryBytes) {

    /** Explicit marker used when the host name cannot be resolved; the host name is never null. */
    public static final String UNKNOWN_HOST_NAME = "unknown";
}
