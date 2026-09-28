// Copyright 2026 the jaxrs-twirp authors
// SPDX-License-Identifier: Apache-2.0

package com.dennyac.twirp.example;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.Map;

/**
 * Small ordinary REST resource used to demonstrate that Twirp and REST failure
 * semantics coexist in the same Dropwizard application.
 */
@Path("/api/greeting")
@Produces(MediaType.APPLICATION_JSON)
public final class ExampleRestResource {

    @GET
    public Map<String, String> greeting() {
        return Map.of("message", "hello");
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, String> echo(Map<String, String> request) {
        return request;
    }
}
