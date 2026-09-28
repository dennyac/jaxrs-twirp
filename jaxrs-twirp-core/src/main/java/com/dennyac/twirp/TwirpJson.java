// Copyright 2026 the jaxrs-twirp authors
// SPDX-License-Identifier: Apache-2.0

package com.dennyac.twirp;

import com.google.protobuf.util.JsonFormat;

/**
 * Canonical Twirp JSON codec defaults, shared by the server-side body providers
 * and the client helpers so both ends agree on the wire format out of the box.
 *
 * <p>The defaults mirror the Twirp Go server's behavior:
 * <ul>
 *   <li>{@code preservingProtoFieldNames()} — emit snake_case proto field names
 *       rather than camelCase.</li>
 *   <li>{@code alwaysPrintFieldsWithNoPresence()} — emit fields without
 *       presence (proto3 scalars not marked {@code optional}, repeated fields
 *       and maps) even when they hold default values, matching
 *       {@code EmitUnpopulated: true}. Unset fields with presence, such as
 *       message and proto2 {@code optional} fields, are omitted; Go prints
 *       {@code null} for them.</li>
 *   <li>{@code omittingInsignificantWhitespace()} — produce compact JSON.</li>
 *   <li>parser {@code ignoringUnknownFields()} — tolerate unknown fields,
 *       matching {@code DiscardUnknown: true}.</li>
 * </ul>
 *
 * <p>This class has no Dropwizard dependency; the Dropwizard {@code TwirpBundle}
 * delegates here for its own defaults.
 */
public final class TwirpJson {

    private TwirpJson() {
        // utility class
    }

    /**
     * The default JSON printer: snake_case field names, fields without presence
     * printed even when they hold default values, no insignificant whitespace.
     */
    public static JsonFormat.Printer defaultPrinter() {
        return JsonFormat.printer()
                .preservingProtoFieldNames()
                .alwaysPrintFieldsWithNoPresence()
                .omittingInsignificantWhitespace();
    }

    /** The default JSON parser silently ignores unknown fields. */
    public static JsonFormat.Parser defaultParser() {
        return JsonFormat.parser().ignoringUnknownFields();
    }
}
