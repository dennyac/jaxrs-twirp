// Copyright 2026 the jaxrs-twirp authors
// SPDX-License-Identifier: Apache-2.0

package com.dennyac.twirp.resteasy;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/rest")
public class RestResource {

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String get() {
        return "ordinary REST";
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public RestMessage post(RestMessage message) {
        return message;
    }

    @GET
    @Path("/json")
    @Produces(MediaType.APPLICATION_JSON)
    public RestMessage json() {
        return new RestMessage("ordinary REST");
    }

    public record RestMessage(String displayName) {
    }
}
