// Copyright 2026 the jaxrs-twirp authors
// SPDX-License-Identifier: Apache-2.0

package com.dennyac.twirp.errors;

import com.google.protobuf.InvalidProtocolBufferException;
import com.dennyac.twirp.ErrorCode;
import com.dennyac.twirp.TwirpError;
import com.dennyac.twirp.TwirpMediaTypes;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Maps {@link InvalidProtocolBufferException}s — thrown by
 * {@link com.dennyac.twirp.codec.ProtobufMessageBodyReader} when the request
 * body cannot be parsed — to a Twirp {@link ErrorCode#MALFORMED} JSON response
 * (HTTP 400).
 */
@Provider
public class InvalidProtocolBufferExceptionMapper
        implements ExceptionMapper<InvalidProtocolBufferException> {

    @Override
    public Response toResponse(InvalidProtocolBufferException exception) {
        return Response.status(ErrorCode.MALFORMED.httpStatus())
                .type(TwirpMediaTypes.APPLICATION_JSON_TYPE)
                .entity(TwirpError.of(ErrorCode.MALFORMED,
                        "the request payload could not be decoded: " + exception.getMessage()))
                .build();
    }
}
