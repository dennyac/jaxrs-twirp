package com.dennyac.twirp.codec;

import com.google.protobuf.Message;
import com.dennyac.twirp.TwirpMediaTypes;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.Provider;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.lang.reflect.Type;

/**
 * Jersey {@link MessageBodyReader} for {@code application/protobuf} request bodies.
 *
 * <p>Reads raw proto3 wire bytes from the request body into the concrete
 * {@link Message} subtype declared by the resource method. The concrete type's
 * static {@code getDefaultInstance()} method is invoked once via reflection and
 * the prototype is cached per-class for subsequent requests.
 */
@Provider
@Consumes(TwirpMediaTypes.APPLICATION_PROTOBUF)
public class ProtobufMessageBodyReader implements MessageBodyReader<Message> {

    private static final ConcurrentMap<Class<?>, Message> PROTOTYPES = new ConcurrentHashMap<>();

    @Override
    public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations,
                              MediaType mediaType) {
        return Message.class.isAssignableFrom(type)
                && TwirpMediaTypes.APPLICATION_PROTOBUF_TYPE.isCompatible(mediaType);
    }

    @Override
    public Message readFrom(Class<Message> type, Type genericType, Annotation[] annotations,
                            MediaType mediaType, MultivaluedMap<String, String> httpHeaders,
                            InputStream entityStream) throws IOException, WebApplicationException {
        Message prototype = defaultInstanceOf(type);
        Message.Builder builder = prototype.newBuilderForType();
        builder.mergeFrom(entityStream);
        return builder.build();
    }

    static Message defaultInstanceOf(Class<?> type) {
        Message cached = PROTOTYPES.get(type);
        if (cached != null) {
            return cached;
        }
        Message prototype = invokeGetDefaultInstance(type);
        PROTOTYPES.putIfAbsent(type, prototype);
        return prototype;
    }

    private static Message invokeGetDefaultInstance(Class<?> type) {
        try {
            Method method = type.getMethod("getDefaultInstance");
            if (!Modifier.isStatic(method.getModifiers())) {
                throw new IllegalArgumentException(
                        type.getName() + ".getDefaultInstance() is not static");
            }
            Object result = method.invoke(null);
            if (!(result instanceof Message message)) {
                throw new IllegalArgumentException(
                        type.getName() + ".getDefaultInstance() did not return a Message");
            }
            return message;
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException(
                    "no getDefaultInstance() found on " + type.getName()
                            + "; is it a protoc-generated Message subclass?", e);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException(
                    "unable to invoke " + type.getName() + ".getDefaultInstance()", e);
        }
    }
}
