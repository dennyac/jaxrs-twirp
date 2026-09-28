// Copyright 2026 the jaxrs-twirp authors
// SPDX-License-Identifier: Apache-2.0

package com.dennyac.twirp.protoc;

import com.google.protobuf.compiler.PluginProtos.CodeGeneratorRequest;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorResponse;

/**
 * Plugin entry point invoked by {@code protoc}.
 *
 * <p>protoc starts this binary, writes a {@link CodeGeneratorRequest} to its
 * stdin, and reads a {@link CodeGeneratorResponse} back from its stdout. We
 * delegate the actual work to {@link Plugin#generate(CodeGeneratorRequest)} so
 * the orchestration is testable in-process.
 *
 * <p>Codegen options normally arrive through protoc's
 * {@code --twirp_java_out=<options>:<outdir>} parameter. Build integrations that
 * cannot set that parameter may pass {@code key=value} options as command-line
 * arguments; these are merged into the same option string.
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        CodeGeneratorRequest request = CodeGeneratorRequest.parseFrom(System.in);
        String parameter = mergeOptions(request.getParameter(), args);
        if (!parameter.equals(request.getParameter())) {
            request = request.toBuilder().setParameter(parameter).build();
        }
        CodeGeneratorResponse response;
        try {
            response = new Plugin().generate(request);
        } catch (Exception e) {
            // Report errors through the protoc response rather than crashing so
            // protoc surfaces a clean error message to the user.
            StringBuilder sb = new StringBuilder("jaxrs-twirp-protoc failed: ")
                    .append(e.getClass().getSimpleName())
                    .append(": ")
                    .append(e.getMessage());
            response = CodeGeneratorResponse.newBuilder()
                    .setError(sb.toString())
                    .build();
        }
        response.writeTo(System.out);
        System.out.flush();
    }

    static String mergeOptions(String parameter, String[] args) {
        StringBuilder merged = new StringBuilder(parameter == null ? "" : parameter);
        if (args != null) {
            for (String arg : args) {
                if (arg == null || arg.isBlank()) {
                    continue;
                }
                if (merged.length() > 0) {
                    merged.append(',');
                }
                merged.append(arg.trim());
            }
        }
        return merged.toString();
    }

    private Main() {
        // entry point only
    }
}
