package com.dennyac.twirp;

import java.util.concurrent.Callable;

/**
 * Helper invoked by generated Twirp resource classes to run a service method
 * call and convert any unhandled exception into a {@link TwirpException} so that
 * the response is always Twirp-formatted.
 *
 * <p>{@link TwirpException}s thrown by the service are re-raised unchanged.
 * {@code InterruptedException}s reset the thread's interrupted status. Any
 * other {@link Exception} is wrapped in {@code TwirpException(ErrorCode.INTERNAL, ...)};
 * {@link Error}s and other non-{@code Exception} throwables propagate untouched.
 */
public final class TwirpInvocations {

    private TwirpInvocations() {
        // utility class
    }

    /**
     * Invoke a Twirp service method, wrapping any unhandled exception as
     * {@link ErrorCode#INTERNAL}.
     *
     * @param methodName the RPC method name, used in the error message
     * @param work       the service call
     * @param <T>        the response message type
     * @return whatever {@code work} returns
     * @throws TwirpException if {@code work} throws anything
     */
    public static <T> T invoke(String methodName, Callable<T> work) {
        try {
            return work.call();
        } catch (TwirpException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TwirpException(ErrorCode.UNAVAILABLE,
                    methodName + " was interrupted", e);
        } catch (Exception e) {
            throw new TwirpException(ErrorCode.INTERNAL,
                    methodName + " failed: " + e.getMessage(), e);
        }
    }
}
