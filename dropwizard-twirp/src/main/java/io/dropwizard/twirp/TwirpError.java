package io.dropwizard.twirp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JSON-serializable shape of a Twirp error response body:
 * <pre>
 * {
 *   "code": "not_found",
 *   "msg":  "hat 42 does not exist",
 *   "meta": { "key": "value" }     // optional, omitted when empty
 * }
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonPropertyOrder({"code", "msg", "meta"})
public final class TwirpError {

    private final String code;
    private final String msg;
    private final Map<String, String> meta;

    public TwirpError(
            @JsonProperty("code") String code,
            @JsonProperty("msg")  String msg,
            @JsonProperty("meta") Map<String, String> meta) {
        this.code = code;
        this.msg = msg;
        this.meta = meta == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(meta));
    }

    public static TwirpError of(TwirpException exception) {
        return new TwirpError(
                exception.getErrorCode().wireValue(),
                exception.getMessage(),
                exception.getMeta());
    }

    public static TwirpError of(ErrorCode code, String message) {
        return new TwirpError(code.wireValue(), message, Collections.emptyMap());
    }

    @JsonProperty("code")
    public String getCode() {
        return code;
    }

    @JsonProperty("msg")
    public String getMsg() {
        return msg;
    }

    @JsonProperty("meta")
    public Map<String, String> getMeta() {
        return meta;
    }
}
