package io.dropwizard.twirp.protoc;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileOptions;
import com.squareup.javapoet.ClassName;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves proto type references (fully-qualified names beginning with a dot,
 * e.g. {@code .com.example.Hat}) to {@link ClassName}s for the corresponding
 * protoc-Java generated types.
 *
 * <p>Honors the standard Java code-generation options on each
 * {@link FileDescriptorProto}:
 * <ul>
 *   <li>{@code java_package} — overrides the proto package.</li>
 *   <li>{@code java_multiple_files} — if true, each top-level message is its
 *       own Java class. If false (the default), all messages are nested inside
 *       a single outer class.</li>
 *   <li>{@code java_outer_classname} — name of that single outer class.</li>
 * </ul>
 *
 * <p>Nested messages are also resolved — their Java form is
 * {@code Outer.Inner.Leaf}.
 */
public final class TypeMapper {

    /** Maps fully-qualified proto type name (with leading dot) → Java {@link ClassName}. */
    private final Map<String, ClassName> messageTypes = new HashMap<>();

    public static TypeMapper fromFiles(List<FileDescriptorProto> files) {
        TypeMapper mapper = new TypeMapper();
        for (FileDescriptorProto file : files) {
            mapper.registerFile(file);
        }
        return mapper;
    }

    /**
     * Look up the Java {@link ClassName} for the given proto type, e.g.
     * {@code .com.example.Hat}. Throws if the type was not found in any of the
     * files registered with this mapper.
     */
    public ClassName resolve(String fullyQualifiedProtoName) {
        ClassName cn = messageTypes.get(fullyQualifiedProtoName);
        if (cn == null) {
            throw new IllegalArgumentException(
                    "no Java type registered for proto type " + fullyQualifiedProtoName
                            + " (was the message's .proto file passed to protoc?)");
        }
        return cn;
    }

    private void registerFile(FileDescriptorProto file) {
        String javaPackage = javaPackageOf(file);
        String outer = outerClassNameOf(file);
        boolean multipleFiles = file.getOptions().getJavaMultipleFiles();
        String protoPackage = file.getPackage();
        String protoPrefix = protoPackage.isEmpty() ? "." : "." + protoPackage + ".";

        for (DescriptorProto msg : file.getMessageTypeList()) {
            registerMessage(msg, javaPackage, outer, multipleFiles, protoPrefix);
        }
    }

    private void registerMessage(DescriptorProto msg, String javaPackage,
                                 String outer, boolean multipleFiles, String protoPrefix) {
        String protoName = protoPrefix + msg.getName();

        ClassName javaName;
        if (multipleFiles) {
            javaName = ClassName.get(javaPackage, msg.getName());
        } else {
            javaName = ClassName.get(javaPackage, outer, msg.getName());
        }
        messageTypes.put(protoName, javaName);

        // Recurse for nested messages — their Java form is always nested inside
        // the parent class, regardless of java_multiple_files.
        for (DescriptorProto nested : msg.getNestedTypeList()) {
            registerNested(nested, javaName, protoName + ".");
        }
    }

    private void registerNested(DescriptorProto nested, ClassName parent, String protoPrefix) {
        ClassName nestedClass = parent.nestedClass(nested.getName());
        messageTypes.put(protoPrefix + nested.getName(), nestedClass);
        for (DescriptorProto deeper : nested.getNestedTypeList()) {
            registerNested(deeper, nestedClass, protoPrefix + nested.getName() + ".");
        }
    }

    static String javaPackageOf(FileDescriptorProto file) {
        FileOptions options = file.getOptions();
        if (options.hasJavaPackage() && !options.getJavaPackage().isEmpty()) {
            return options.getJavaPackage();
        }
        return file.getPackage();
    }

    static String outerClassNameOf(FileDescriptorProto file) {
        FileOptions options = file.getOptions();
        if (options.hasJavaOuterClassname() && !options.getJavaOuterClassname().isEmpty()) {
            return options.getJavaOuterClassname();
        }
        return JavaNaming.defaultOuterClassName(file.getName());
    }
}
