// Copyright 2026 the jaxrs-twirp authors
// SPDX-License-Identifier: Apache-2.0

package com.dennyac.twirp;

import java.util.concurrent.Callable;

/**
 * Helper invoked by generated Twirp resource classes to run a service method
 * call and convert any unhandled exception into a {@link TwirpException} so that
 * the response is always Twirp-formatted.
 *
 * <p>{@link TwirpException}s thrown by the service are re-raised unchanged.
 * {@code InterruptedException}s reset the thread's interrupted status. Any
 * other {@link Exception}, and any {@link Error} except a
 * {@link VirtualMachineError}, is wrapped in
 * {@code TwirpException(ErrorCode.INTERNAL, ...)} with the original as its cause.
 * {@code VirtualMachineError}s such as {@link OutOfMemoryError} propagate
 * untouched. A {@code null} result also becomes {@link ErrorCode#INTERNAL};
 * services return the message's default instance for an empty response.
 */
public final class TwirpInvocations {

    private TwirpInvocations() {
        // utility class
    }

    /**
     * Invoke a Twirp service method, wrapping any unhandled exception or error as
     * {@link ErrorCode#INTERNAL}.
     *
     * @param methodName the RPC method name, used in the error message
     * @param work       the service call
     * @param <T>        the response message type
     * @return whatever {@code work} returns, never {@code null}
     * @throws TwirpException if {@code work} throws anything other than a
     *         {@link VirtualMachineError}, or returns {@code null}
     */
    public static <T> T invoke(String methodName, Callable<T> work) {
        T result;
        try {
            result = work.call();
        } catch (TwirpException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TwirpException(ErrorCode.UNAVAILABLE,
                    methodName + " was interrupted", e);
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Exception | Error e) {
            throw new TwirpException(ErrorCode.INTERNAL,
                    methodName + " failed: " + e.getMessage(), e);
        }
        if (result == null) {
            throw new TwirpException(ErrorCode.INTERNAL,
                    "received a null response while calling " + methodName
                            + "; null responses are not supported");
        }
        return result;
    }
}
