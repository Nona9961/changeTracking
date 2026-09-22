package com.nona.changeTracking.bench.env;

import java.util.List;

/**
 * Port that reads the raw environment facts of the running JVM.
 * <p>
 * It is the single seam between the environment record and the JVM runtime, so record
 * assembly and failure handling can be tested without touching the real runtime.
 */
public interface EnvironmentProbe {

    /**
     * Returns the JDK version of the running JVM.
     *
     * @return the JDK version, never null or blank
     */
    String jdkVersion();

    /**
     * Returns the JVM input arguments of the running JVM.
     *
     * @return the JVM input arguments, never null (possibly empty)
     */
    List<String> jvmArguments();

    /**
     * Returns the names of the garbage collectors active in the running JVM.
     *
     * @return the garbage collector names, never null (possibly empty)
     */
    List<String> gcCollectors();

    /**
     * Returns the host name of the benchmark machine.
     *
     * @return the host name, or null when it cannot be resolved
     */
    String hostName();

    /**
     * Returns the number of processors available to the running JVM.
     *
     * @return the available processors, at least 1 in a healthy JVM
     */
    int availableProcessors();

    /**
     * Returns the maximum heap size of the running JVM.
     *
     * @return the maximum heap size in bytes, at least 1 in a healthy JVM
     */
    long maxMemoryBytes();
}
