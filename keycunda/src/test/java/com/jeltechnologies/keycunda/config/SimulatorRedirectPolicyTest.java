package com.jeltechnologies.keycunda.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SimulatorRedirectPolicyTest {

    private final SimulatorRedirectPolicy policy = SimulatorRedirectPolicy.of(null, "camunda-26.example.com");

    @Test
    void domainDefaultsToTheCamundaDomainWithoutItsFirstLabel() {
        assertThat(policy.allowedDomain()).isEqualTo("example.com");
        assertThat(SimulatorRedirectPolicy.of("", "example.com").allowedDomain()).isEqualTo("example.com");
        assertThat(SimulatorRedirectPolicy.of("Demo.Org", "camunda-26.example.com").allowedDomain()).isEqualTo("demo.org");
    }

    @Test
    void acceptsHttpsOnTheDomainAndItsSubdomainsWithAnyPath() {
        assertThat(policy.allows("https://ticket.example.com/login/oauth2/code/keycunda")).isTrue();
        assertThat(policy.allows("https://a.b.example.com/any/path?x=1")).isTrue();
        assertThat(policy.allows("https://example.com/")).isTrue();
        assertThat(policy.allows("https://TICKET.Example.COM/x")).isTrue();
    }

    @Test
    void acceptsHttpOnlyOnLocalhost() {
        assertThat(policy.allows("http://localhost:13014/login/oauth2/code/keycunda")).isTrue();
        assertThat(policy.allows("http://127.0.0.1:13014/")).isTrue();
        assertThat(policy.allows("http://ticket.example.com/")).isFalse();
    }

    @Test
    void rejectsEverythingElse() {
        assertThat(policy.allows("https://evil.com/")).isFalse();
        assertThat(policy.allows("https://example.com.evil.com/")).isFalse();
        assertThat(policy.allows("https://evilexample.com/")).isFalse();
        assertThat(policy.allows("https://user@ticket.example.com/")).isFalse();
        assertThat(policy.allows("https://ticket.example.com/#frag")).isFalse();
        assertThat(policy.allows("javascript:alert(1)")).isFalse();
        assertThat(policy.allows("/relative")).isFalse();
        assertThat(policy.allows("")).isFalse();
        assertThat(policy.allows(null)).isFalse();
    }
}
