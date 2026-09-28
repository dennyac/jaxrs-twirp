// Copyright 2026 the jaxrs-twirp authors
// SPDX-License-Identifier: Apache-2.0

package com.dennyac.twirp.resteasy;

import com.dennyac.twirp.TwirpContext;
import com.dennyac.twirp.TwirpException;

import java.security.Principal;

final class EchoService implements Echo {

    @Override
    public EchoMessage echo(EchoMessage request, TwirpContext context) {
        return request;
    }

    @Override
    public EchoMessage reject(EchoMessage request, TwirpContext context) {
        throw TwirpException.invalidArgument("hat_size", "must be positive");
    }

    @Override
    public EchoMessage returnNull(EchoMessage request, TwirpContext context) {
        return null;
    }

    @Override
    public EchoMessage crash(EchoMessage request, TwirpContext context) {
        throw new AssertionError("invariant broken");
    }

    @Override
    public HeaderResponse readHeader(HeaderRequest request, TwirpContext context) {
        return HeaderResponse.newBuilder()
                .addAllValues(context.headerValues(request.getName()))
                .build();
    }

    @Override
    public WhoAmIResponse whoAmI(WhoAmIRequest request, TwirpContext context) {
        return WhoAmIResponse.newBuilder()
                .setSubject(context.principal().map(Principal::getName).orElse(""))
                .build();
    }
}
