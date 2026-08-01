package com.jeltechnologies.keycunda.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class SecretYamlCodecTest {

    private final SecretYamlCodec codec = new SecretYamlCodec();

    private static Secret secret(String key, String value) {
        return new Secret(key, value);
    }

    @Test
    void exportsAKindSecretManifestWithTheGivenName() {
        String yaml = codec.export(List.of(secret("API_KEY", "abc123")), "my-connector-secrets");
        assertThat(yaml).contains("kind: Secret");
        assertThat(yaml).contains("name: my-connector-secrets");
        assertThat(yaml).contains("stringData:");
        assertThat(yaml).contains("API_KEY: abc123");
    }

    @Test
    void exportThenParseRoundTripsIncludingMultilineValues() {
        String multiline = "{\n  \"a\": 1,\n  \"b\": \"has \\\"quotes\\\" and a colon: here\"\n}\n";
        List<Secret> secrets = List.of(secret("PLAIN", "value1"), secret("JSON_BLOB", multiline));

        String yaml = codec.export(secrets, SecretYamlCodec.DEFAULT_SECRET_NAME);
        Map<String, String> parsed = codec.parse(yaml);

        assertThat(parsed).containsEntry("PLAIN", "value1");
        assertThat(parsed).containsEntry("JSON_BLOB", multiline);
    }

    @Test
    void parsesBase64EncodedDataEntries() {
        String yaml = """
                apiVersion: v1
                kind: Secret
                metadata:
                  name: example
                type: Opaque
                data:
                  FOO: %s
                """.formatted(Base64.getEncoder().encodeToString("bar".getBytes()));

        Map<String, String> parsed = codec.parse(yaml);
        assertThat(parsed).containsEntry("FOO", "bar");
    }

    @Test
    void rejectsAManifestWithNoDataOrStringData() {
        String yaml = """
                apiVersion: v1
                kind: Secret
                metadata:
                  name: example
                """;
        assertThatThrownBy(() -> codec.parse(yaml)).isInstanceOf(IllegalArgumentException.class);
    }
}
