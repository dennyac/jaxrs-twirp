package com.dennyac.twirp;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.FeatureContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

final class TwirpAuthFeatureTest {

    private final ContainerRequestFilter authFilter = request -> {
    };

    @Test
    void bindsFilterOnlyToSelectedRpc() throws Exception {
        TwirpAuthFeature feature = TwirpAuthFeature.forRpcs(
                authFilter, TestResource.class, "Secret");
        List<Object> registered = new ArrayList<>();
        FeatureContext context = recordingContext(registered);

        feature.configure(resourceInfo(TestResource.class.getMethod("secret")), context);
        feature.configure(resourceInfo(TestResource.class.getMethod("publicRpc")), context);

        assertThat(registered).containsExactly(authFilter);
    }

    @Test
    void protectsAllPostRpcMethodsButNotOrdinaryRestMethods() throws Exception {
        TwirpAuthFeature feature = TwirpAuthFeature.forAllRpcs(authFilter, TestResource.class);
        List<Object> registered = new ArrayList<>();
        FeatureContext context = recordingContext(registered);

        feature.configure(resourceInfo(TestResource.class.getMethod("secret")), context);
        feature.configure(resourceInfo(TestResource.class.getMethod("publicRpc")), context);
        feature.configure(resourceInfo(TestResource.class.getMethod("status")), context);

        assertThat(registered).containsExactly(authFilter, authFilter);
    }

    @Test
    void rejectsUnknownRpcAtStartup() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> TwirpAuthFeature.forRpcs(
                        authFilter, TestResource.class, "Missing"))
                .withMessageContaining("Missing")
                .withMessageContaining(TestResource.class.getName());
    }

    private static ResourceInfo resourceInfo(Method method) {
        return new ResourceInfo() {
            @Override
            public Method getResourceMethod() {
                return method;
            }

            @Override
            public Class<?> getResourceClass() {
                return method.getDeclaringClass();
            }
        };
    }

    private static FeatureContext recordingContext(List<Object> registered) {
        Object[] holder = new Object[1];
        FeatureContext context = (FeatureContext) Proxy.newProxyInstance(
                TwirpAuthFeatureTest.class.getClassLoader(),
                new Class<?>[]{FeatureContext.class},
                (ignored, method, args) -> {
                    if ("register".equals(method.getName()) && args != null && args.length > 0) {
                        registered.add(args[0]);
                        return holder[0];
                    }
                    if ("toString".equals(method.getName())) {
                        return "recording FeatureContext";
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        holder[0] = context;
        return context;
    }

    @Path("/twirp/example.Test")
    public static final class TestResource {
        @POST
        @Path("/Secret")
        public void secret() {
        }

        @POST
        @Path("/Public")
        public void publicRpc() {
        }

        @GET
        @Path("/status")
        public void status() {
        }
    }
}
