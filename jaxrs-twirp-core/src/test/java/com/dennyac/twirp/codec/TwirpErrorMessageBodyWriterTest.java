// Copyright 2026 the jaxrs-twirp authors
// SPDX-License-Identifier: Apache-2.0

package com.dennyac.twirp.codec;

import com.dennyac.twirp.ErrorCode;
import com.dennyac.twirp.TwirpError;
import com.dennyac.twirp.TwirpMediaTypes;
import com.google.protobuf.Message;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;

class TwirpErrorMessageBodyWriterTest {

    private static final Annotation[] NO_ANNOTATIONS = new Annotation[0];
    private final TwirpErrorMessageBodyWriter writer = new TwirpErrorMessageBodyWriter();

    @Test
    void onlyWritesTwirpErrorsAsJson() {
        assertThat(isWriteable(TwirpError.class, MediaType.APPLICATION_JSON_TYPE)).isTrue();
        assertThat(isWriteable(TwirpError.class,
                MediaType.valueOf("application/json; charset=UTF-8"))).isTrue();
        assertThat(isWriteable(TwirpError.class, TwirpMediaTypes.APPLICATION_PROTOBUF_TYPE)).isFalse();
        assertThat(isWriteable(TwirpError.class, MediaType.TEXT_PLAIN_TYPE)).isFalse();
        assertThat(isWriteable(Object.class, MediaType.APPLICATION_JSON_TYPE)).isFalse();
        assertThat(isWriteable(Map.class, MediaType.APPLICATION_JSON_TYPE)).isFalse();
        assertThat(isWriteable(Message.class, MediaType.APPLICATION_JSON_TYPE)).isFalse();
    }

    @Test
    void omitsEmptyMetadata() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        write(TwirpError.of(ErrorCode.NOT_FOUND, "no such hat"), output);

        assertThat(output.toString(StandardCharsets.UTF_8))
                .isEqualTo("{\"code\":\"not_found\",\"msg\":\"no such hat\"}");
    }

    @Test
    void preservesPropertyOrderMetadataEscapingAndUtf8() throws Exception {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("argument", "hat_color");
        meta.put("reason", "use \"red\"");
        TwirpError error = new TwirpError("invalid_argument", "hat \"\u00e9\"\nnot found", meta);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        write(error, output);

        assertThat(output.toString(StandardCharsets.UTF_8)).isEqualTo(
                "{\"code\":\"invalid_argument\",\"msg\":\"hat \\\"\u00e9\\\"\\nnot found\","
                        + "\"meta\":{\"argument\":\"hat_color\",\"reason\":\"use \\\"red\\\"\"}}");
    }

    @Test
    void leavesTheJaxrsOutputStreamOpen() throws Exception {
        TrackingOutputStream output = new TrackingOutputStream();

        write(TwirpError.of(ErrorCode.INTERNAL, "failed"), output);

        assertThat(output.closed).isFalse();
        assertThat(output.size()).isPositive();
    }

    @Test
    void propagatesOutputFailures() {
        OutputStream output = new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                throw new IOException("write failed");
            }
        };

        assertThatIOException()
                .isThrownBy(() -> write(TwirpError.of(ErrorCode.INTERNAL, "failed"), output))
                .withMessage("write failed");
    }

    private boolean isWriteable(Class<?> type, MediaType mediaType) {
        return writer.isWriteable(type, type, NO_ANNOTATIONS, mediaType);
    }

    private void write(TwirpError error, OutputStream output) throws IOException {
        writer.writeTo(error, TwirpError.class, TwirpError.class, NO_ANNOTATIONS,
                MediaType.APPLICATION_JSON_TYPE, new MultivaluedHashMap<>(), output);
    }

    private static final class TrackingOutputStream extends ByteArrayOutputStream {
        boolean closed;

        @Override
        public void close() {
            closed = true;
        }
    }
}
