package com.dennyac.twirp.protoc;

import javax.lang.model.SourceVersion;

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
     *
     * <p>If the result collides with a Java reserved word (e.g. an RPC named
     * {@code Return} or {@code Import} lowercases to {@code return}/{@code import}),
     * it is suffixed with an underscore so the generated source compiles. The
     * Twirp URL path is unaffected — it always uses the original proto RPC name —
     * so wire compatibility is preserved.
     */
    public static String lowerCamelMethodName(String name) {
        return escapeJavaKeyword(toLowerCamel(name));
    }

    private static String toLowerCamel(String name) {
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
     * Suffix {@code candidate} with {@code _} when it is a Java reserved word —
     * a keyword or one of the reserved literals {@code true}/{@code false}/{@code null}.
     * Other names are returned unchanged.
     */
    static String escapeJavaKeyword(String candidate) {
        if (candidate != null && !candidate.isEmpty() && SourceVersion.isKeyword(candidate)) {
            return candidate + "_";
        }
        return candidate;
    }

    /**
     * Infer the Java wrapper name from the file's basename, stripping
     * {@code .proto} or {@code .protodevel} and converting to UpperCamelCase.
     *
     * <p>{@link TypeMapper} appends {@code OuterClass} when this name matches
     * a message, enum or service declared in the file, including nested types.
     * With {@code java_multiple_files=false}, message references in generated
     * Java code are nested inside that resolved wrapper.
     */
    public static String defaultOuterClassName(String fileName) {
        String base = fileName;
        int slash = base.lastIndexOf('/');
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        if (base.endsWith(".proto")) {
            base = base.substring(0, base.length() - ".proto".length());
        } else if (base.endsWith(".protodevel")) {
            base = base.substring(0, base.length() - ".protodevel".length());
        }
        return underscoresToCamelCase(base, true);
    }

    static String underscoresToCamelCase(String input, boolean capNext) {
        StringBuilder result = new StringBuilder(input.length());
        boolean cap = capNext;
        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            if (ch >= 'a' && ch <= 'z') {
                result.append(cap ? Character.toUpperCase(ch) : ch);
                cap = false;
            } else if (ch >= 'A' && ch <= 'Z') {
                result.append(i == 0 && !cap ? Character.toLowerCase(ch) : ch);
                cap = false;
            } else if (ch >= '0' && ch <= '9') {
                result.append(ch);
                cap = true;
            } else {
                cap = true;
            }
        }
        if (input.endsWith("#")) {
            result.append('_');
        }
        return result.toString();
    }
}
