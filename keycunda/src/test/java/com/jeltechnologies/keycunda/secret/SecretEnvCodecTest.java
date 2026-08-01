package com.jeltechnologies.keycunda.secret;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class SecretEnvCodecTest {

    private final SecretEnvCodec codec = new SecretEnvCodec();

    // A synthetic .env fixture - no real or realistic-looking credential material, since a prior
    // version of this file (a plausible-looking fake GCP service account key, flagged by GitHub's
    // push protection even though it was never a real key) shouldn't have been committed in the
    // first place. MULTI_LINE_VALUE exercises the same "unquoted, multi-physical-line value"
    // parsing the real feature exists for, just with obviously fake content.
    private static final String SAMPLE_ENV = """
            AWS_ACCESS_KEY_ID=TESTKEY1
            CLUSTER_URL=TESTURL1
            MULTI_LINE_VALUE={
            "note": "spans several physical lines",
            "continued": "with no KEY= prefix on these lines"
            }
            NEXT_KEY=TESTNEXT1
            SLACK_TOKEN=TESTSLACKTOKEN1""";

    @Test
    void parsesSimpleSingleLineValues() {
        Map<String, String> parsed = codec.parse(SAMPLE_ENV);
        assertThat(parsed).containsEntry("AWS_ACCESS_KEY_ID", "TESTKEY1");
        assertThat(parsed).containsEntry("CLUSTER_URL", "TESTURL1");
    }

    @Test
    void parsesTheRawMultilineValueIntact() {
        Map<String, String> parsed = codec.parse(SAMPLE_ENV);
        String multiline = parsed.get("MULTI_LINE_VALUE");
        assertThat(multiline).startsWith("{");
        assertThat(multiline).endsWith("}");
        assertThat(multiline).contains("spans several physical lines");
        assertThat(multiline).contains("with no KEY= prefix on these lines");
    }

    @Test
    void doesNotCorruptTheKeyImmediatelyFollowingAMultilineValue() {
        Map<String, String> parsed = codec.parse(SAMPLE_ENV);
        assertThat(parsed).containsEntry("NEXT_KEY", "TESTNEXT1");
    }

    @Test
    void doesNotAppendATrailingBlankLineToTheLastValue() {
        Map<String, String> parsed = codec.parse(SAMPLE_ENV);
        assertThat(parsed).containsEntry("SLACK_TOKEN", "TESTSLACKTOKEN1");
    }

    @Test
    void exportThenParseRoundTrips() {
        Map<String, String> original = codec.parse(SAMPLE_ENV);

        List<Secret> secrets = new ArrayList<>();
        original.forEach((key, value) -> secrets.add(new Secret(key, value)));

        Map<String, String> roundTripped = codec.parse(codec.export(secrets));
        assertThat(roundTripped).isEqualTo(original);
    }
}
