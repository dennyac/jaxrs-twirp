package com.dennyac.twirp;

import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.DynamicFeature;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.FeatureContext;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Binds a JAX-RS authentication request filter to selected generated Twirp RPCs.
 *
 * <p>The helper resolves proto RPC names from the generated methods'
 * {@link Path} annotations when it is built, so misspelled names fail during
 * application startup instead of silently leaving an endpoint unprotected.
 *
 * <pre>{@code
 * environment.jersey().register(TwirpAuthFeature.forRpcs(
 *         authFilter,
 *         HaberdasherResource.class,
 *         "WhoAmI"));
 * }</pre>
 */
public final class TwirpAuthFeature implements DynamicFeature {

    private final ContainerRequestFilter authFilter;
    private final Set<Method> protectedMethods;

    private TwirpAuthFeature(ContainerRequestFilter authFilter, Set<Method> protectedMethods) {
        this.authFilter = authFilter;
        this.protectedMethods = Set.copyOf(protectedMethods);
    }

    /**
     * Protect selected proto RPC names on one generated resource class.
     */
    public static TwirpAuthFeature forRpcs(ContainerRequestFilter authFilter,
                                           Class<?> resourceClass,
                                           String... rpcNames) {
        return builder(authFilter).protect(resourceClass, rpcNames).build();
    }

    /**
     * Protect every generated RPC on one resource class.
     */
    public static TwirpAuthFeature forAllRpcs(ContainerRequestFilter authFilter,
                                              Class<?> resourceClass) {
        return builder(authFilter).protectAll(resourceClass).build();
    }

    /**
     * Build a policy spanning multiple generated resource classes.
     */
    public static Builder builder(ContainerRequestFilter authFilter) {
        return new Builder(authFilter);
    }

    @Override
    public void configure(ResourceInfo resourceInfo, FeatureContext context) {
        Method resourceMethod = resourceInfo.getResourceMethod();
        if (resourceMethod != null && protectedMethods.contains(resourceMethod)) {
            context.register(authFilter);
        }
    }

    public static final class Builder {
        private final ContainerRequestFilter authFilter;
        private final Set<Method> protectedMethods = new LinkedHashSet<>();

        private Builder(ContainerRequestFilter authFilter) {
            this.authFilter = Objects.requireNonNull(authFilter, "authFilter");
        }

        public Builder protect(Class<?> resourceClass, String... rpcNames) {
            Objects.requireNonNull(resourceClass, "resourceClass");
            Objects.requireNonNull(rpcNames, "rpcNames");
            if (rpcNames.length == 0) {
                throw new IllegalArgumentException("at least one RPC name is required");
            }
            for (String rpcName : rpcNames) {
                protectedMethods.add(resolveRpc(resourceClass, rpcName));
            }
            return this;
        }

        public Builder protectAll(Class<?> resourceClass) {
            Objects.requireNonNull(resourceClass, "resourceClass");
            Method[] rpcMethods = Arrays.stream(resourceClass.getMethods())
                    .filter(TwirpAuthFeature::isRpcMethod)
                    .toArray(Method[]::new);
            if (rpcMethods.length == 0) {
                throw new IllegalArgumentException(
                        resourceClass.getName() + " has no generated Twirp RPC methods");
            }
            protectedMethods.addAll(Arrays.asList(rpcMethods));
            return this;
        }

        public TwirpAuthFeature build() {
            if (protectedMethods.isEmpty()) {
                throw new IllegalStateException("at least one protected Twirp RPC is required");
            }
            return new TwirpAuthFeature(authFilter, protectedMethods);
        }

        private static Method resolveRpc(Class<?> resourceClass, String rpcName) {
            String normalizedName = normalizeRpcName(rpcName);
            return Arrays.stream(resourceClass.getMethods())
                    .filter(TwirpAuthFeature::isRpcMethod)
                    .filter(method -> normalizedName.equals(
                            normalizePath(method.getAnnotation(Path.class).value())))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "no Twirp RPC named " + normalizedName + " on " + resourceClass.getName()));
        }

        private static String normalizeRpcName(String rpcName) {
            Objects.requireNonNull(rpcName, "rpcName");
            String normalized = normalizePath(rpcName.trim());
            if (normalized.isEmpty() || normalized.contains("/")) {
                throw new IllegalArgumentException("invalid Twirp RPC name: " + rpcName);
            }
            return normalized;
        }
    }

    private static boolean isRpcMethod(Method method) {
        return method.isAnnotationPresent(POST.class) && method.isAnnotationPresent(Path.class);
    }

    private static String normalizePath(String path) {
        int start = 0;
        int end = path.length();
        while (start < end && path.charAt(start) == '/') {
            start++;
        }
        while (end > start && path.charAt(end - 1) == '/') {
            end--;
        }
        return path.substring(start, end);
    }
}
