package io.dropwizard.twirp.protoc;

import com.google.protobuf.compiler.PluginProtos.CodeGeneratorRequest;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorResponse;

/**
 * Plugin entry point invoked by {@code protoc}.
 *
 * <p>protoc starts this binary, writes a {@link CodeGeneratorRequest} to its
 * stdin, and reads a {@link CodeGeneratorResponse} back from its stdout. We
 * delegate the actual work to {@link Plugin#generate(CodeGeneratorRequest)} so
 * the orchestration is testable in-process.
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        CodeGeneratorRequest request = CodeGeneratorRequest.parseFrom(System.in);
        CodeGeneratorResponse response;
        try {
            response = new Plugin().generate(request);
        } catch (Exception e) {
            // Report errors through the protoc response rather than crashing so
            // protoc surfaces a clean error message to the user.
            StringBuilder sb = new StringBuilder("twirp-protoc failed: ")
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

    private Main() {
        // entry point only
    }
}
