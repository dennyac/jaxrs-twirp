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

    @Test
    void clientGenerationDefaultsToTrue() {
        assertThat(Options.parse(null).generateClient()).isTrue();
        assertThat(Options.parse("prefix=/foo").generateClient()).isTrue();
    }

    @Test
    void clientGenerationCanBeDisabled() {
        assertThat(Options.parse("client=false").generateClient()).isFalse();
        assertThat(Options.parse("client=no").generateClient()).isFalse();
        assertThat(Options.parse("client=0").generateClient()).isFalse();
    }

    @Test
    void clientOptionAcceptsTruthyAliases() {
        assertThat(Options.parse("client=true").generateClient()).isTrue();
        assertThat(Options.parse("client=yes").generateClient()).isTrue();
        assertThat(Options.parse("client=1").generateClient()).isTrue();
    }

    @Test
    void clientOptionFallsBackOnGarbageValue() {
        // unrecognized values fall back to default rather than throwing
        assertThat(Options.parse("client=maybe").generateClient()).isTrue();
    }

    @Test
    void serverGenerationDefaultsToTrue() {
        assertThat(Options.parse(null).generateServer()).isTrue();
        assertThat(Options.parse("prefix=/foo").generateServer()).isTrue();
    }

    @Test
    void serverGenerationCanBeDisabled() {
        assertThat(Options.parse("server=false").generateServer()).isFalse();
        assertThat(Options.parse("server=no").generateServer()).isFalse();
        assertThat(Options.parse("server=0").generateServer()).isFalse();
    }

    @Test
    void clientAndServerOptionsParseIndependently() {
        Options both = Options.parse("client=false,server=false");
        assertThat(both.generateClient()).isFalse();
        assertThat(both.generateServer()).isFalse();

        Options serverOnly = Options.parse("client=false");
        assertThat(serverOnly.generateClient()).isFalse();
        assertThat(serverOnly.generateServer()).isTrue();

        Options clientOnly = Options.parse("server=false");
        assertThat(clientOnly.generateClient()).isTrue();
        assertThat(clientOnly.generateServer()).isFalse();
    }
}
