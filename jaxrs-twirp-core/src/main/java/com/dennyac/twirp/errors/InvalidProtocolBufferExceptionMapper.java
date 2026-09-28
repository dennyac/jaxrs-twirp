// Copyright 2026 the jaxrs-twirp authors
// SPDX-License-Identifier: Apache-2.0

package com.dennyac.twirp.errors;

import com.dennyac.twirp.ErrorCode;
import com.dennyac.twirp.TwirpError;
import com.dennyac.twirp.TwirpMediaTypes;
import com.dennyac.twirp.codec.MalformedMessageException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Maps {@link MalformedMessageException}s — thrown by
 * {@link com.dennyac.twirp.codec.ProtobufMessageBodyReader} and
 * {@link com.dennyac.twirp.codec.ProtobufJsonMessageBodyReader} when the request
 * body cannot be decoded — to a Twirp {@link ErrorCode#MALFORMED} JSON response
 * (HTTP 400).
 *
 * <p>Other {@link com.google.protobuf.InvalidProtocolBufferException}s, such as
 * one thrown while an ordinary REST resource parses stored data, are left to the
 * application's own exception mappers.
 */
@Provider
public class InvalidProtocolBufferExceptionMapper
        implements ExceptionMapper<MalformedMessageException> {

    @Override
    public Response toResponse(MalformedMessageException exception) {
        return Response.status(ErrorCode.MALFORMED.httpStatus())
                .type(TwirpMediaTypes.APPLICATION_JSON_TYPE)
                .entity(TwirpError.of(ErrorCode.MALFORMED,
                        "the request payload could not be decoded: " + exception.getMessage()))
                .build();
    }
}
