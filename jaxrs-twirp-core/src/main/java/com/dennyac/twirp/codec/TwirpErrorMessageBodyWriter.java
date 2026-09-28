// Copyright 2026 the jaxrs-twirp authors
// SPDX-License-Identifier: Apache-2.0

package com.dennyac.twirp.codec;

import com.dennyac.twirp.TwirpError;
import com.dennyac.twirp.TwirpMediaTypes;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.Provider;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

/** Writes Twirp error envelopes without requiring an application-wide JSON provider. */
@Provider
@Produces(TwirpMediaTypes.APPLICATION_JSON)
public final class TwirpErrorMessageBodyWriter implements MessageBodyWriter<TwirpError> {

    private static final ObjectWriter WRITER = new ObjectMapper()
            .writerFor(TwirpError.class)
            .without(JsonGenerator.Feature.AUTO_CLOSE_TARGET);

    @Override
    public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations,
                               MediaType mediaType) {
        return type == TwirpError.class
                && TwirpMediaTypes.APPLICATION_JSON_TYPE.isCompatible(mediaType);
    }

    @Override
    public void writeTo(TwirpError error, Class<?> type, Type genericType, Annotation[] annotations,
                        MediaType mediaType, MultivaluedMap<String, Object> httpHeaders,
                        OutputStream entityStream) throws IOException {
        WRITER.writeValue(entityStream, error);
    }
}
