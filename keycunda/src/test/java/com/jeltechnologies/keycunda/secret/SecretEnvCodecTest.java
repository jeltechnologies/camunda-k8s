package com.jeltechnologies.keycunda.secret;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.util.StreamUtils;

class SecretEnvCodecTest {

    private final SecretEnvCodec codec = new SecretEnvCodec();

    private static String loadSampleEnv() throws IOException {
        try (InputStream in = SecretEnvCodecTest.class.getClassLoader().getResourceAsStream("sample-secrets.env")) {
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        }
    }

    @Test
    void parsesSimpleSingleLineValues() throws IOException {
        Map<String, String> parsed = codec.parse(loadSampleEnv());
        assertThat(parsed).containsEntry("AWS_ACCESS_KEY_ID", "TESTKEY1");
        assertThat(parsed).containsEntry("CLUSTER_URL", "TESTURL1");
    }

    @Test
    void parsesTheRawMultilineJsonValueIntact() throws IOException {
        Map<String, String> parsed = codec.parse(loadSampleEnv());
        String serviceAccount = parsed.get("IDP_GCP_SERVICE_ACCOUNT");
        assertThat(serviceAccount).startsWith("{");
        assertThat(serviceAccount).endsWith("}");
        assertThat(serviceAccount).contains("\"client_email\": \"test-user@test.com\"");
        assertThat(serviceAccount).contains("-----BEGIN PRIVATE KEY-----");
        assertThat(serviceAccount).contains("-----END PRIVATE KEY-----");
    }

    @Test
    void doesNotCorruptTheKeyImmediatelyFollowingAMultilineValue() throws IOException {
        Map<String, String> parsed = codec.parse(loadSampleEnv());
        assertThat(parsed).containsEntry("IDP_GCP_VERTEX_BUCKET_NAME", "TESTVERTEXBUCKET1");
    }

    @Test
    void doesNotAppendATrailingBlankLineToTheLastValue() throws IOException {
        Map<String, String> parsed = codec.parse(loadSampleEnv());
        assertThat(parsed).containsEntry("SLACK_TOKEN", "TESTSLACKTOKEN1");
    }

    @Test
    void exportThenParseRoundTrips() throws IOException {
        Map<String, String> original = codec.parse(loadSampleEnv());

        List<Secret> secrets = new ArrayList<>();
        original.forEach((key, value) -> secrets.add(new Secret(key, value)));

        Map<String, String> roundTripped = codec.parse(codec.export(secrets));
        assertThat(roundTripped).isEqualTo(original);
    }
}
