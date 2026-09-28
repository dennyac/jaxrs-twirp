// Copyright 2026 the jaxrs-twirp authors
// SPDX-License-Identifier: Apache-2.0

package com.dennyac.twirp.codec;

import com.google.protobuf.Any;
import com.google.protobuf.Message;
import com.google.protobuf.TypeRegistry;
import com.google.protobuf.util.JsonFormat;
import com.dennyac.twirp.TwirpJson;
import com.dennyac.twirp.TwirpMediaTypes;
import com.dennyac.twirp.testproto.Parcel;
import com.dennyac.twirp.testproto.TestMessage;
import jakarta.ws.rs.core.MultivaluedHashMap;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins down the single proto feature whose JSON handling is not automatic:
 * {@code google.protobuf.Any}.
 *
 * <p>The protobuf {@code JsonFormat} printer/parser can only render or read an
 * {@code Any} field when they have been given a {@link TypeRegistry} that knows
 * the packed message type. Without one, JSON serialization fails; binary
 * protobuf is unaffected. These tests both document that limitation and prove
 * that {@code TwirpBundle.Builder.typeRegistry} (and, equivalently, supplying a
 * registry-equipped printer/parser to the body providers) resolves it.
 */
class AnyJsonCodecTest {

    private static final Parcel PARCEL = Parcel.newBuilder()
            .setLabel("box")
            .setContents(Any.pack(TestMessage.newBuilder()
                    .setHatColor("red")
                    .setHatSize(7)
                    .build()))
            .build();

    private static final TypeRegistry REGISTRY = TypeRegistry.newBuilder()
            .add(TestMessage.getDescriptor())
            .build();

    @Test
    void anyFieldCannotBeWrittenAsJsonWithoutARegistry() {
        // The default providers (what TwirpBundle installs out of the box) have
        // no TypeRegistry, so printing an Any throws. This is the documented
        // limitation.
        ProtobufJsonMessageBodyWriter writer = new ProtobufJsonMessageBodyWriter();

        assertThatThrownBy(() -> writer.writeTo(
                PARCEL, Parcel.class, null, null, TwirpMediaTypes.APPLICATION_JSON_TYPE,
                new MultivaluedHashMap<>(), new ByteArrayOutputStream()))
                .isInstanceOf(IOException.class);
    }

    @Test
    void anyFieldStillRoundTripsAsBinaryProtobufWithoutARegistry() throws Exception {
        // The same Parcel is perfectly happy on the binary wire — the Any type
        // URL travels inline, so no registry is required.
        ProtobufMessageBodyWriter writer = new ProtobufMessageBodyWriter();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writer.writeTo(PARCEL, Parcel.class, null, null, TwirpMediaTypes.APPLICATION_PROTOBUF_TYPE,
                new MultivaluedHashMap<>(), out);

        Parcel back = Parcel.parseFrom(out.toByteArray());
        assertThat(back).isEqualTo(PARCEL);
        assertThat(back.getContents().unpack(TestMessage.class).getHatColor()).isEqualTo("red");
    }

    @Test
    void anyFieldRoundTripsAsJsonWithARegistry() throws Exception {
        JsonFormat.Printer printer = TwirpJson.defaultPrinter().usingTypeRegistry(REGISTRY);
        JsonFormat.Parser parser = TwirpJson.defaultParser().usingTypeRegistry(REGISTRY);

        ProtobufJsonMessageBodyWriter writer = new ProtobufJsonMessageBodyWriter(printer);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writer.writeTo(PARCEL, Parcel.class, null, null, TwirpMediaTypes.APPLICATION_JSON_TYPE,
                new MultivaluedHashMap<>(), out);
        String json = out.toString(StandardCharsets.UTF_8);

        // The canonical Any JSON form carries an @type URL plus the packed
        // message's own (snake_case) fields.
        assertThat(json).contains("\"@type\":\"type.googleapis.com/com.dennyac.twirp.testproto.TestMessage\"");
        assertThat(json).contains("\"hat_color\":\"red\"");

        @SuppressWarnings("unchecked")
        Class<Message> parcelType = (Class<Message>) (Class<?>) Parcel.class;
        ProtobufJsonMessageBodyReader reader = new ProtobufJsonMessageBodyReader(parser);
        Message back = reader.readFrom(parcelType, Parcel.class, null,
                TwirpMediaTypes.APPLICATION_JSON_TYPE, new MultivaluedHashMap<>(),
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));

        assertThat(back).isInstanceOf(Parcel.class);
        assertThat(((Parcel) back).getContents().unpack(TestMessage.class).getHatColor())
                .isEqualTo("red");
    }
}
