package com.nona.changeTracking.bench.env;

import java.util.List;

/**
 * Environment facts captured at benchmark run time and archived next to the JMH result JSON.
 *
 * @param jdkVersion   JDK version of the benchmark JVM
 * @param jvmArguments JVM input arguments of the benchmark JVM, never null
 * @param gcCollectors names of the garbage collectors active in the benchmark JVM, never null
 * @param machine      machine identity of the benchmark host
 */
public record EnvironmentRecord(String jdkVersion,
                               List<String> jvmArguments,
                               List<String> gcCollectors,
                               MachineIdentity machine) {

    /**
     * Defensive copy constructor: the record owns immutable copies of the captured lists.
     */
    public EnvironmentRecord {
        jvmArguments = List.copyOf(jvmArguments);
        gcCollectors = List.copyOf(gcCollectors);
    }

    /**
     * Renders this record as JSON using the frozen environment record shape, so results can be
     * archived and compared without a JSON dependency.
     *
     * @return the JSON representation of this record
     */
    public String toJson() {
        return "{\"jdkVersion\":\"" + escape(jdkVersion) + "\""
                + ",\"jvmArguments\":" + toJsonArray(jvmArguments)
                + ",\"gcCollectors\":" + toJsonArray(gcCollectors)
                + ",\"machine\":{\"hostName\":\"" + escape(machine.hostName()) + "\""
                + ",\"availableProcessors\":" + machine.availableProcessors()
                + ",\"maxMemoryBytes\":" + machine.maxMemoryBytes() + "}}";
    }

    /**
     * Renders a list of strings as a JSON array.
     *
     * @param values the values to render, never null
     * @return the JSON array text
     */
    private static String toJsonArray(final List<String> values) {
        final StringBuilder builder = new StringBuilder("[");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append('"').append(escape(values.get(index))).append('"');
        }
        return builder.append(']').toString();
    }

    /**
     * Escapes the characters that must not appear raw inside a JSON string.
     *
     * @param value the raw value, never null
     * @return the escaped value
     */
    private static String escape(final String value) {
        final StringBuilder builder = new StringBuilder(value.length() + 8);
        for (int index = 0; index < value.length(); index++) {
            final char character = value.charAt(index);
            switch (character) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\t' -> builder.append("\\t");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                default -> {
                    if (character < 0x20) {
                        builder.append(String.format("\\u%04x", (int) character));
                    } else {
                        builder.append(character);
                    }
                }
            }
        }
        return builder.toString();
    }
}