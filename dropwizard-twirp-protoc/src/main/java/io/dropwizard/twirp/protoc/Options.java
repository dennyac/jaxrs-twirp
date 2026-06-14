package io.dropwizard.twirp.protoc;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Plugin parameters passed via {@code --twirp_java_out=key1=value1,key2=value2:OUT}.
 *
 * <p>Supported keys:
 * <ul>
 *   <li>{@code prefix} — the URL path prefix prepended to every generated
 *       {@code @Path}. Default: {@code /twirp}. Leading slash is normalized;
 *       trailing slash is stripped.</li>
 * </ul>
 *
 * <p>Unknown keys are ignored so users on a newer plugin version can pass keys
 * an older protoc-gen-twirp_java doesn't know about.
 */
public final class Options {

    public static final String DEFAULT_PREFIX = "/twirp";

    private final String pathPrefix;
    private final Map<String, String> raw;

    private Options(String pathPrefix, Map<String, String> raw) {
        this.pathPrefix = pathPrefix;
        this.raw = Collections.unmodifiableMap(raw);
    }

    public String pathPrefix() {
        return pathPrefix;
    }

    public Map<String, String> raw() {
        return raw;
    }

    /**
     * Parse a parameter string of the form {@code k1=v1,k2=v2}. Empty / null
     * input yields the defaults.
     */
    public static Options parse(String parameter) {
        Map<String, String> raw = new LinkedHashMap<>();
        if (parameter != null && !parameter.isEmpty()) {
            for (String pair : parameter.split(",")) {
                if (pair.isEmpty()) {
                    continue;
                }
                int eq = pair.indexOf('=');
                if (eq < 0) {
                    raw.put(pair.trim(), "");
                } else {
                    raw.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
                }
            }
        }
        String prefix = raw.getOrDefault("prefix", DEFAULT_PREFIX);
        return new Options(normalizePrefix(prefix), raw);
    }

    static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return "";
        }
        String result = prefix;
        if (!result.startsWith("/")) {
            result = "/" + result;
        }
        while (result.length() > 1 && result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
