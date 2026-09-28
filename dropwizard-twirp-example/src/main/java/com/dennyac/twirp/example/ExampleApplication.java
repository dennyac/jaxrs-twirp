// Copyright 2026 the jaxrs-twirp authors
// SPDX-License-Identifier: Apache-2.0

package com.dennyac.twirp.example;

import io.dropwizard.auth.AuthFilter;
import io.dropwizard.auth.Authenticator;
import io.dropwizard.auth.Authorizer;
import io.dropwizard.auth.PrincipalImpl;
import io.dropwizard.auth.UnauthorizedHandler;
import io.dropwizard.auth.oauth.OAuthCredentialAuthFilter;
import io.dropwizard.core.Application;
import io.dropwizard.core.setup.Bootstrap;
import io.dropwizard.core.setup.Environment;
import com.dennyac.twirp.TwirpAuthFeature;
import com.dennyac.twirp.TwirpBundle;
import com.dennyac.twirp.example.haberdasher.HaberdasherResource;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.security.Principal;
import java.util.Optional;

/**
 * Dropwizard application exposing the Haberdasher Twirp service.
 *
 * <p>The interesting bits are <b>elsewhere</b>:
 *
 * <ul>
 *   <li><b>Code generation</b> happens at build time via
 *       {@code protobuf-maven-plugin} wired in {@code pom.xml}. Every
 *       {@code mvn compile} regenerates protobuf message classes plus the
 *       Twirp service interface, JAX-RS resource, and JAX-RS client into
 *       {@code target/generated-sources/protobuf/java/}. See
 *       {@code README.md} for the configuration and for how non-Maven
 *       users can drive {@code jaxrs-twirp-protoc} as a raw protoc
 *       plugin instead.</li>
 *
 *   <li>The <b>runtime wiring</b> for a Twirp service in a Dropwizard app
 *       is exactly what you see in this class:
 *       <ol>
 *         <li>Add {@link TwirpBundle} during {@link #initialize(Bootstrap)}
 *             so the protobuf/JSON body readers/writers and the Twirp error
 *             mapper are registered with Jersey.</li>
 *         <li>Register your generated {@code <Service>Resource} in
 *             {@link #run}, passing it an implementation of the generated
 *             service interface.</li>
 *       </ol>
 *       That's the whole story — no separate server.</li>
 * </ul>
 */
public class ExampleApplication extends Application<ExampleConfiguration> {

    public static void main(String[] args) throws Exception {
        new ExampleApplication().run(args);
    }

    @Override
    public String getName() {
        return "dropwizard-twirp-example";
    }

    @Override
    public void initialize(Bootstrap<ExampleConfiguration> bootstrap) {
        bootstrap.addBundle(new TwirpBundle<>());
    }

    @Override
    public void run(ExampleConfiguration configuration, Environment environment) {
        environment.jersey().register(new HaberdasherResource(new HaberdasherImpl()));
        environment.jersey().register(new ExampleRestResource());
        environment.jersey().register(TwirpAuthFeature.forRpcs(
                whoAmIAuthFilter(), HaberdasherResource.class, "WhoAmI"));
    }

    /**
     * Bind bearer-token authentication to the generated {@code whoAmI} method.
     * Generated methods do not carry Dropwizard's {@code @Auth} parameter, so a
     * {@link TwirpAuthFeature} attaches the auth filter to the selected proto RPC.
     */
    private static AuthFilter<String, Principal> whoAmIAuthFilter() {
        Authenticator<String, Principal> authenticator = token -> switch (token) {
            case "admin-token" -> Optional.of(new PrincipalImpl("admin"));
            case "user-token" -> Optional.of(new PrincipalImpl("alice"));
            default -> Optional.empty();
        };

        Authorizer<Principal> authorizer = (principal, role, requestContext) ->
                "admin".equals(principal.getName()) || "user".equals(role);

        UnauthorizedHandler unauthorizedHandler = new UnauthorizedHandler() {
            @Override
            public Response buildResponse(String prefix, String realm) {
                return Response.status(Response.Status.UNAUTHORIZED)
                        .type(MediaType.APPLICATION_JSON)
                        .entity("{\"code\":\"unauthenticated\","
                                + "\"msg\":\"authentication required\",\"meta\":{}}")
                        .build();
            }
        };

        return new OAuthCredentialAuthFilter.Builder<Principal>()
                .setAuthenticator(authenticator)
                .setAuthorizer(authorizer)
                .setPrefix("Bearer")
                .setUnauthorizedHandler(unauthorizedHandler)
                .buildAuthFilter();
    }
}
