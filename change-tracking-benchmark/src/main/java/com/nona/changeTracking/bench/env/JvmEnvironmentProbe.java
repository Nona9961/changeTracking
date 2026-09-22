package com.nona.changeTracking.bench.env;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * Environment probe backed by the JDK runtime APIs of the running JVM.
 * <p>
 * Every fact is read from the {@code java.lang.management} and {@code System} APIs;
 * the host name degrades to {@link MachineIdentity#UNKNOWN_HOST_NAME} when resolution fails.
 */
public final class JvmEnvironmentProbe implements EnvironmentProbe {

    /**
     * Creates a probe reading the JVM this code runs in.
     */
    public JvmEnvironmentProbe() {
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String jdkVersion() {
        return System.getProperty("java.version");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<String> jvmArguments() {
        return List.copyOf(ManagementFactory.getRuntimeMXBean().getInputArguments());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<String> gcCollectors() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .map(GarbageCollectorMXBean::getName)
                .toList();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (final UnknownHostException e) {
            return MachineIdentity.UNKNOWN_HOST_NAME;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int availableProcessors() {
        return Runtime.getRuntime().availableProcessors();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long maxMemoryBytes() {
        return Runtime.getRuntime().maxMemory();
    }
}