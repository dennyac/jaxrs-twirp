// Copyright 2026 the jaxrs-twirp authors
// SPDX-License-Identifier: Apache-2.0

package com.dennyac.twirp.codec;

import com.google.protobuf.InvalidProtocolBufferException;

/**
 * Thrown by the Twirp message body readers when a protobuf or JSON body cannot
 * be decoded, including a proto2 message that is missing {@code required} fields.
 *
 * <p>On the server, {@link com.dennyac.twirp.errors.InvalidProtocolBufferExceptionMapper}
 * renders it as a Twirp {@code malformed} error (HTTP 400). Other
 * {@link InvalidProtocolBufferException}s are left to the application's own
 * exception mappers. On the client, JAX-RS wraps it in a
 * {@link jakarta.ws.rs.ProcessingException} like any other reader
 * {@link java.io.IOException}.
 */
public class MalformedMessageException extends InvalidProtocolBufferException {

    private static final long serialVersionUID = 1L;

    public MalformedMessageException(InvalidProtocolBufferException cause) {
        super(cause.getMessage(), cause);
    }
}
