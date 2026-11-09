package com.jeltechnologies.keycunda.login;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class RedirectPatternsTest {

    @Test
    void parseKeepsOnePatternPerNonBlankLineWithoutDuplicates() {
        assertThat(RedirectPatterns.parse(" https://a.example.com/x \r\n\r\nhttps://b.example.com/y\nhttps://a.example.com/x\n"))
                .containsExactly("https://a.example.com/x", "https://b.example.com/y");
        assertThat(RedirectPatterns.parse(null)).isEmpty();
    }

    @Test
    void validateAcceptsHttpsAndLocalhostHttp() {
        for (String ok : List.of("https://ticket.example.com/login/oauth2/code/keycunda", "https://ticket.example.com/*",
                "https://*.example.com/*", "https://example.com:8443/cb", "http://localhost:13014/*", "http://127.0.0.1/cb")) {
            assertThat(RedirectPatterns.validate(ok)).as(ok).isNull();
        }
    }

    @Test
    void validateRejectsRiskyOrBrokenPatterns() {
        for (String bad : List.of("", "ticket.example.com/cb", "http://ticket.example.com/cb", "ftp://x.example.com/",
                "https://*/cb", "https://*.com/cb", "https://ticket.*.example.com/cb", "https://tick*.example.com/cb",
                "https://user@ticket.example.com/cb", "https://ticket.example.com/cb#frag", "https://ticket.example.com/a b",
                "https://ticket.example.com:port/cb", "https:///cb")) {
            assertThat(RedirectPatterns.validate(bad)).as(bad).isNotNull();
        }
    }

    @Test
    void exactPatternMatchesOnlyThatUri() {
        List<String> patterns = List.of("https://ticket.example.com/login/oauth2/code/keycunda");
        assertThat(RedirectPatterns.anyMatches(patterns, "https://ticket.example.com/login/oauth2/code/keycunda")).isTrue();
        assertThat(RedirectPatterns.anyMatches(patterns, "https://ticket.example.com/login/oauth2/code/other")).isFalse();
        assertThat(RedirectPatterns.anyMatches(patterns, "https://ticket.example.com/login/oauth2/code/keycunda/x")).isFalse();
    }

    @Test
    void wildcardsMatchPathsAndSubdomains() {
        assertThat(RedirectPatterns.anyMatches(List.of("https://ticket.example.com/*"), "https://ticket.example.com/a/b?c=d")).isTrue();
        assertThat(RedirectPatterns.anyMatches(List.of("https://*.example.com/cb"), "https://a.b.example.com/cb")).isTrue();
        assertThat(RedirectPatterns.anyMatches(List.of("https://*.example.com/cb"), "https://ticket.example.com/cb")).isTrue();
        assertThat(RedirectPatterns.anyMatches(List.of("https://*.example.com/cb"), "https://example.com/cb")).isFalse();
        assertThat(RedirectPatterns.anyMatches(List.of("https://*.example.com/cb"), "https://example.com.evil.com/cb")).isFalse();
        assertThat(RedirectPatterns.anyMatches(List.of("https://*.example.com/cb"), "https://evilexample.com/cb")).isFalse();
        assertThat(RedirectPatterns.anyMatches(List.of("https://*.example.com/*"), "https://x.example.com@evil.com/")).isFalse();
    }

    @Test
    void hostAndSchemeIgnoreCaseButPathDoesNot() {
        assertThat(RedirectPatterns.anyMatches(List.of("https://ticket.example.com/Cb"), "HTTPS://TICKET.example.com/Cb")).isTrue();
        assertThat(RedirectPatterns.anyMatches(List.of("https://ticket.example.com/Cb"), "https://ticket.example.com/cb")).isFalse();
    }

    @Test
    void nothingMatchesWithoutPatternsOrWithUnsafeUris() {
        assertThat(RedirectPatterns.anyMatches(null, "https://ticket.example.com/")).isFalse();
        assertThat(RedirectPatterns.anyMatches(List.of("https://ticket.example.com/*"), "https://ticket.example.com/#frag")).isFalse();
        assertThat(RedirectPatterns.anyMatches(List.of("https://ticket.example.com/*"), "/relative")).isFalse();
        assertThat(RedirectPatterns.anyMatches(List.of("https://ticket.example.com/*"), null)).isFalse();
    }
}
