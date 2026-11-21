// Copyright 2026 the jaxrs-twirp authors
// SPDX-License-Identifier: Apache-2.0

package com.dennyac.twirp;

import com.dennyac.twirp.testproto.Proto2Message;
import com.dennyac.twirp.testproto.TestMessage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TwirpJsonTest {

    @Test
    void defaultPrinterEmitsProto3DefaultValues() throws Exception {
        String json = TwirpJson.defaultPrinter().print(TestMessage.getDefaultInstance());

        assertThat(json).isEqualTo("{\"hat_color\":\"\",\"hat_size\":0,\"tags\":[]}");
    }

    @Test
    void defaultPrinterOmitsUnsetProto2OptionalFields() throws Exception {
        Proto2Message message = Proto2Message.newBuilder().setName("fedora").setCount(0).build();

        String json = TwirpJson.defaultPrinter().print(message);

        assertThat(json).isEqualTo("{\"name\":\"fedora\",\"count\":0}");
    }
}
