// Copyright 2026 the jaxrs-twirp authors
// SPDX-License-Identifier: Apache-2.0

package com.dennyac.twirp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TwirpErrorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void omitsMetaWhenEmpty() throws Exception {
        TwirpError error = new TwirpError("not_found", "no such hat", Map.of());

        String json = mapper.writeValueAsString(error);

        assertThat(json).isEqualTo("{\"code\":\"not_found\",\"msg\":\"no such hat\"}");
    }

    @Test
    void includesMetaWhenPresent() throws Exception {
        TwirpError error = new TwirpError("unavailable", "back soon",
                Map.of("retry_after", "15s"));

        String json = mapper.writeValueAsString(error);

        assertThat(json).contains("\"meta\":{\"retry_after\":\"15s\"}");
    }

    @Test
    void buildsFromTwirpException() {
        TwirpException ex = TwirpException.builder(ErrorCode.RESOURCE_EXHAUSTED)
                .message("over quota")
                .meta("retry_after", "60s")
                .build();

        TwirpError error = TwirpError.of(ex);

        assertThat(error.getCode()).isEqualTo("resource_exhausted");
        assertThat(error.getMsg()).isEqualTo("over quota");
        assertThat(error.getMeta()).containsEntry("retry_after", "60s");
    }

    @Test
    void httpStatusForResourceExhaustedIs429() {
        // flit and fajran both got this wrong (403); verify we don't.
        assertThat(ErrorCode.RESOURCE_EXHAUSTED.httpStatus()).isEqualTo(429);
    }

    @Test
    void allWireValuesRoundTrip() {
        for (ErrorCode code : ErrorCode.values()) {
            assertThat(ErrorCode.fromWireValue(code.wireValue())).isEqualTo(code);
        }
    }

    @Test
    void unknownWireValueMapsToUnknown() {
        assertThat(ErrorCode.fromWireValue("definitely_not_a_code")).isEqualTo(ErrorCode.UNKNOWN);
        assertThat(ErrorCode.fromWireValue(null)).isEqualTo(ErrorCode.UNKNOWN);
    }
}
