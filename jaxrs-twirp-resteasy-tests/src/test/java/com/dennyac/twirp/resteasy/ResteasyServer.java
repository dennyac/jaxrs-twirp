// Copyright 2026 the jaxrs-twirp authors
// SPDX-License-Identifier: Apache-2.0

package com.dennyac.twirp.resteasy;

import com.dennyac.twirp.TwirpAuthFeature;
import com.dennyac.twirp.TwirpServerFeature;
import jakarta.ws.rs.SeBootstrap;
import jakarta.ws.rs.core.Application;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.net.URI;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Starts RESTEasy through {@link SeBootstrap} on a free port, serving the
 * generated {@link EchoResource} next to {@link RestResource}.
 */
final class ResteasyServer implements BeforeAllCallback, AfterAllCallback {

    private SeBootstrap.Instance instance;

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        SeBootstrap.Configuration configuration = SeBootstrap.Configuration.builder()
                .host("localhost")
                .port(SeBootstrap.Configuration.FREE_PORT)
                .build();
        instance = SeBootstrap.start(new TestApplication(), configuration)
                .toCompletableFuture()
                .get(30, TimeUnit.SECONDS);
    }

    @Override
    public void afterAll(ExtensionContext context) throws Exception {
        if (instance != null) {
            instance.stop().toCompletableFuture().get(30, TimeUnit.SECONDS);
        }
    }

    SeBootstrap.Instance instance() {
        return instance;
    }

    URI baseUri() {
        return instance.configuration().baseUri();
    }

    URI uri(String path) {
        return baseUri().resolve(path);
    }

    private static final class TestApplication extends Application {
        private final Set<Object> singletons = Set.of(
                new TwirpServerFeature(),
                new EchoResource(new EchoService()),
                TwirpAuthFeature.forRpcs(new BearerTokenFilter(), EchoResource.class, "WhoAmI"),
                new RestResource(),
                new JacksonConfig());

        @Override
        @SuppressWarnings("deprecation")
        public Set<Object> getSingletons() {
            return singletons;
        }
    }
}
