// Copyright 2026 the jaxrs-twirp authors
// SPDX-License-Identifier: Apache-2.0

package com.dennyac.twirp.codec;

import com.google.protobuf.Message;
import com.dennyac.twirp.TwirpMediaTypes;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.Provider;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

/**
 * Jersey {@link MessageBodyWriter} for {@code application/protobuf} response bodies.
 *
 * <p>Writes a {@link Message} via {@link Message#writeTo(OutputStream)} — raw
 * proto3 wire bytes, no framing.
 */
@Provider
@Produces(TwirpMediaTypes.APPLICATION_PROTOBUF)
public class ProtobufMessageBodyWriter implements MessageBodyWriter<Message> {

    @Override
    public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations,
                               MediaType mediaType) {
        return Message.class.isAssignableFrom(type)
                && TwirpMediaTypes.APPLICATION_PROTOBUF_TYPE.isCompatible(mediaType);
    }

    @Override
    public void writeTo(Message message, Class<?> type, Type genericType, Annotation[] annotations,
                        MediaType mediaType, MultivaluedMap<String, Object> httpHeaders,
                        OutputStream entityStream) throws IOException, WebApplicationException {
        message.writeTo(entityStream);
    }
}
