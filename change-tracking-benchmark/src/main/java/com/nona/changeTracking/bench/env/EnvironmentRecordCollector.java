package com.nona.changeTracking.bench.env;

import java.util.List;
import java.util.Objects;

/**
 * Assembles the run time environment record from an {@link EnvironmentProbe}.
 * <p>
 * Validation runs before the record is built: a missing JDK version, a null argument or
 * collector list, or a non positive processor count or heap size fails loudly instead of
 * archiving a record that cannot be mapped back to an environment. A missing host name is
 * the single degradable fact and falls back to {@link MachineIdentity#UNKNOWN_HOST_NAME}.
 */
public final class EnvironmentRecordCollector {

    /**
     * Private constructor: this class is a static collection entry point.
     */
    private EnvironmentRecordCollector() {
    }

    /**
     * Collects and validates the environment facts read from the given probe.
     *
     * @param probe the environment probe, must not be null
     * @return the validated environment record
     * @throws NullPointerException  if probe is null
     * @throws IllegalStateException if a mandatory fact is missing or not positive;
     *                               failures raised by the probe itself are propagated unchanged
     */
    public static EnvironmentRecord collect(final EnvironmentProbe probe) {
        Objects.requireNonNull(probe, "probe");
        final String jdkVersion = probe.jdkVersion();
        if (jdkVersion == null || jdkVersion.isBlank()) {
            throw new IllegalStateException("Environment fact jdkVersion must not be blank");
        }
        final List<String> jvmArguments = probe.jvmArguments();
        if (jvmArguments == null) {
            throw new IllegalStateException("Environment fact jvmArguments must not be null");
        }
        final List<String> gcCollectors = probe.gcCollectors();
        if (gcCollectors == null) {
            throw new IllegalStateException("Environment fact gcCollectors must not be null");
        }
        final int availableProcessors = probe.availableProcessors();
        if (availableProcessors <= 0) {
            throw new IllegalStateException("Environment fact availableProcessors must be positive, but was "
                    + availableProcessors);
        }
        final long maxMemoryBytes = probe.maxMemoryBytes();
        if (maxMemoryBytes <= 0) {
            throw new IllegalStateException("Environment fact maxMemoryBytes must be positive, but was " + maxMemoryBytes);
        }
        final String hostName = resolveHostName(probe.hostName());
        return new EnvironmentRecord(jdkVersion, jvmArguments, gcCollectors,
                new MachineIdentity(hostName, availableProcessors, maxMemoryBytes));
    }

    /**
     * Collects the environment facts of this JVM using {@link JvmEnvironmentProbe}.
     *
     * @return the validated environment record of the running JVM
     */
    public static EnvironmentRecord capture() {
        return collect(new JvmEnvironmentProbe());
    }

    /**
     * Degrades a missing host name to the explicit unknown marker.
     *
     * @param hostName the host name read from the probe, possibly null or blank
     * @return the host name or {@link MachineIdentity#UNKNOWN_HOST_NAME}
     */
    private static String resolveHostName(final String hostName) {
        if (hostName == null || hostName.isBlank()) {
            return MachineIdentity.UNKNOWN_HOST_NAME;
        }
        return hostName;
    }
}