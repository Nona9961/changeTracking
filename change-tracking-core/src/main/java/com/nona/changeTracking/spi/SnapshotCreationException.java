package com.nona.changeTracking.spi;

/**
 * 当 {@link SnapshotStrategy} 在创建快照过程中遇到严重错误时抛出的自定义、非受检异常。
 * <p>
 * 这封装了底层可能发生的各种异常（例如 {@link IllegalAccessException}），
 * 为框架提供了一个统一的错误处理类型。
 */
public class SnapshotCreationException extends RuntimeException {

    /**
     * 使用指定的原因和详细消息构造一个新的快照创建异常。
     *
     * @param message 详细消息。
     * @param cause   原因（通常是底层的具体异常）。
     */
    public SnapshotCreationException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
