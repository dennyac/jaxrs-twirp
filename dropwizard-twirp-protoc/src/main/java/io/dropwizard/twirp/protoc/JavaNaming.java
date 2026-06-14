package io.dropwizard.twirp.protoc;

/**
 * Java naming conventions used when translating proto names to Java names.
 *
 * <p>protoc itself follows these rules for the {@code java_outer_classname}
 * default and the snake_case → CamelCase translation of field accessor names.
 * We mirror them here for service method names (proto RPC name → Java method
 * name) and for the URL path segments used in {@code @Path}.
 */
public final class JavaNaming {

    private JavaNaming() {
        // utility class
    }

    /**
     * Convert a proto RPC method name (typically {@code UpperCamelCase}) to its
     * Java method form ({@code lowerCamelCase}).
     *
     * <p>If the name starts with multiple uppercase letters followed by a
     * lowercase letter (e.g. {@code HTTPSendBytes}), only the leading uppercase
     * run is lowercased up to the last uppercase letter before the lowercase one
     * ({@code httpSendBytes}). That mirrors common Java naming conventions.
     */
    public static String lowerCamelMethodName(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        if (!Character.isUpperCase(name.charAt(0))) {
            return name;
        }
        int upperRun = 0;
        while (upperRun < name.length() && Character.isUpperCase(name.charAt(upperRun))) {
            upperRun++;
        }
        if (upperRun == 1) {
            return Character.toLowerCase(name.charAt(0)) + name.substring(1);
        }
        if (upperRun == name.length()) {
            return name.toLowerCase();
        }
        // upperRun > 1 and there's a lowercase letter at upperRun.
        // Lower the leading run *except* the last upper character which starts
        // the next "word", e.g. HTTPSendBytes -> httpSendBytes.
        int lowerUntil = upperRun - 1;
        return name.substring(0, lowerUntil).toLowerCase() + name.substring(lowerUntil);
    }

    /**
     * Compute the default {@code java_outer_classname} for a proto file when
     * the option is not explicitly set. protoc takes the basename of the file
     * (without {@code .proto}), converts snake_case to UpperCamelCase, then
     * suffixes {@code Proto} if a top-level message/enum/service collides with
     * the bare name.
     *
     * <p>We use the suffix-free form because the bare name is what users see
     * in their proto file; if there's a collision protoc emits the generated
     * Java files for us anyway — we never embed this name in generated code,
     * we only use it for nesting decisions handled in {@link TypeMapper}.
     */
    public static String defaultOuterClassName(String fileName) {
        String base = fileName;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        if (base.endsWith(".proto")) {
            base = base.substring(0, base.length() - ".proto".length());
        }
        return underscoresToCamelCase(base, true);
    }

    static String underscoresToCamelCase(String input, boolean capNext) {
        StringBuilder result = new StringBuilder(input.length());
        boolean cap = capNext;
        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            if (ch == '_') {
                cap = true;
            } else if (Character.isDigit(ch)) {
                result.append(ch);
                cap = true;
            } else if (cap) {
                result.append(Character.toUpperCase(ch));
                cap = false;
            } else {
                result.append(ch);
            }
        }
        return result.toString();
    }
}
