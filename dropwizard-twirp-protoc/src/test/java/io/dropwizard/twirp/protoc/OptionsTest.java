package io.dropwizard.twirp.protoc;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OptionsTest {

    @Test
    void emptyParameterUsesDefaultPrefix() {
        Options options = Options.parse(null);
        assertThat(options.pathPrefix()).isEqualTo(Options.DEFAULT_PREFIX);
        assertThat(options.raw()).isEmpty();
    }

    @Test
    void emptyStringUsesDefaultPrefix() {
        Options options = Options.parse("");
        assertThat(options.pathPrefix()).isEqualTo(Options.DEFAULT_PREFIX);
    }

    @Test
    void honorsCustomPrefix() {
        Options options = Options.parse("prefix=/api/rpc");
        assertThat(options.pathPrefix()).isEqualTo("/api/rpc");
    }

    @Test
    void normalizesMissingLeadingSlash() {
        Options options = Options.parse("prefix=twirp");
        assertThat(options.pathPrefix()).isEqualTo("/twirp");
    }

    @Test
    void stripsTrailingSlash() {
        Options options = Options.parse("prefix=/twirp/");
        assertThat(options.pathPrefix()).isEqualTo("/twirp");
    }

    @Test
    void parsesMultipleKeysAndKeepsUnknownsForwardCompatible() {
        Options options = Options.parse("prefix=/x,unknown=value,emptykey=");
        assertThat(options.pathPrefix()).isEqualTo("/x");
        assertThat(options.raw())
                .containsEntry("prefix", "/x")
                .containsEntry("unknown", "value")
                .containsEntry("emptykey", "");
    }

    @Test
    void parsesValueOnlyAsEmpty() {
        Options options = Options.parse("flag");
        assertThat(options.raw()).containsEntry("flag", "");
    }

    @Test
    void tolerantOfWhitespaceAroundKeysAndValues() {
        Map<String, String> raw = Options.parse(" prefix = /y , extra = z ").raw();
        assertThat(raw).containsEntry("prefix", "/y").containsEntry("extra", "z");
    }
}
