// Copyright 2026 the jaxrs-twirp authors
// SPDX-License-Identifier: Apache-2.0

package com.dennyac.twirp;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TwirpInvocationsTest {

    @Test
    void returnsValueOnSuccess() {
        String result = TwirpInvocations.invoke("Echo", () -> "ok");

        assertThat(result).isEqualTo("ok");
    }

    @Test
    void rethrowsTwirpExceptionUnchanged() {
        TwirpException original = TwirpException.notFound("nope");

        assertThatThrownBy(() -> TwirpInvocations.invoke("Find", (Callable<String>) () -> {
            throw original;
        })).isSameAs(original);
    }

    @Test
    void wrapsArbitraryExceptionsAsInternal() {
        IllegalStateException cause = new IllegalStateException("boom");

        assertThatThrownBy(() -> TwirpInvocations.invoke("Boom", (Callable<String>) () -> {
            throw cause;
        }))
                .isInstanceOfSatisfying(TwirpException.class, twirp -> {
                    assertThat(twirp.getErrorCode()).isEqualTo(ErrorCode.INTERNAL);
                    assertThat(twirp).hasMessageContaining("Boom failed");
                    assertThat(twirp).hasMessageContaining("boom");
                    assertThat(twirp).hasCause(cause);
                });
    }

    @Test
    void wrapsInterruptedExceptionAsUnavailable() {
        Thread.interrupted(); // clear any pre-existing interrupt
        InterruptedException cause = new InterruptedException("interrupted");

        assertThatThrownBy(() -> TwirpInvocations.invoke("Wait", (Callable<String>) () -> {
            throw cause;
        }))
                .isInstanceOfSatisfying(TwirpException.class, twirp -> {
                    assertThat(twirp.getErrorCode()).isEqualTo(ErrorCode.UNAVAILABLE);
                    assertThat(twirp).hasCause(cause);
                });

        // The invocation should have re-asserted the interrupt status.
        assertThat(Thread.interrupted()).isTrue();
    }

    @Test
    void wrapsErrorsAsInternal() {
        AssertionError cause = new AssertionError("invariant broken");

        assertThatThrownBy(() -> TwirpInvocations.invoke("Check", (Callable<String>) () -> {
            throw cause;
        }))
                .isInstanceOfSatisfying(TwirpException.class, twirp -> {
                    assertThat(twirp.getErrorCode()).isEqualTo(ErrorCode.INTERNAL);
                    assertThat(twirp).hasMessage("Check failed: invariant broken");
                    assertThat(twirp).hasCause(cause);
                });
    }

    @Test
    void omitsDetailWhenThrownMessageIsNullOrBlank() {
        NullPointerException nullMsg = new NullPointerException();
        assertThatThrownBy(() -> TwirpInvocations.invoke("Lookup", (Callable<String>) () -> {
            throw nullMsg;
        }))
                .isInstanceOfSatisfying(TwirpException.class, twirp -> {
                    assertThat(twirp.getErrorCode()).isEqualTo(ErrorCode.INTERNAL);
                    assertThat(twirp).hasMessage("Lookup failed");
                    assertThat(twirp).hasCause(nullMsg);
                });

        IllegalArgumentException blankMsg = new IllegalArgumentException("   ");
        assertThatThrownBy(() -> TwirpInvocations.invoke("Validate", (Callable<String>) () -> {
            throw blankMsg;
        }))
                .isInstanceOfSatisfying(TwirpException.class, twirp -> {
                    assertThat(twirp.getErrorCode()).isEqualTo(ErrorCode.INTERNAL);
                    assertThat(twirp).hasMessage("Validate failed");
                    assertThat(twirp).hasCause(blankMsg);
                });

        AssertionError emptyError = new AssertionError("");
        assertThatThrownBy(() -> TwirpInvocations.invoke("Check", (Callable<String>) () -> {
            throw emptyError;
        }))
                .isInstanceOfSatisfying(TwirpException.class, twirp -> {
                    assertThat(twirp.getErrorCode()).isEqualTo(ErrorCode.INTERNAL);
                    assertThat(twirp).hasMessage("Check failed");
                    assertThat(twirp).hasCause(emptyError);
                });
    }

    @Test
    void propagatesVirtualMachineErrorsUnchanged() {
        StackOverflowError error = new StackOverflowError("too deep");

        assertThatThrownBy(() -> TwirpInvocations.invoke("Recurse", (Callable<String>) () -> {
            throw error;
        })).isSameAs(error);
    }

    @Test
    void nullResultIsInternal() {
        assertThatThrownBy(() -> TwirpInvocations.invoke("MakeHat", () -> null))
                .isInstanceOfSatisfying(TwirpException.class, twirp -> {
                    assertThat(twirp.getErrorCode()).isEqualTo(ErrorCode.INTERNAL);
                    assertThat(twirp).hasMessage("received a null response while calling MakeHat;"
                            + " null responses are not supported");
                    assertThat(twirp).hasNoCause();
                });
    }
}
