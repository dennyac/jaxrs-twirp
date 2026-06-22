package io.dropwizard.twirp;

import com.google.protobuf.util.JsonFormat;

/**
 * Canonical Twirp JSON codec defaults, shared by the server-side body providers
 * and the client helpers so both ends agree on the wire format out of the box.
 *
 * <p>The defaults mirror the Twirp Go server's behavior:
 * <ul>
 *   <li>{@code preservingProtoFieldNames()} — emit snake_case proto field names
 *       rather than camelCase.</li>
 *   <li>{@code includingDefaultValueFields()} — emit zero-value fields, matching
 *       {@code EmitUnpopulated: true}.</li>
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
     * The default JSON printer: snake_case field names, zero-value fields
     * included, no insignificant whitespace.
     */
    public static JsonFormat.Printer defaultPrinter() {
        return JsonFormat.printer()
                .preservingProtoFieldNames()
                .includingDefaultValueFields()
                .omittingInsignificantWhitespace();
    }

    /** The default JSON parser silently ignores unknown fields. */
    public static JsonFormat.Parser defaultParser() {
        return JsonFormat.parser().ignoringUnknownFields();
    }
}
