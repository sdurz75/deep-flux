package org.dual.hexa.oauth2.login.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AllowPolicyTest {

    @Test
    void emailsAreTrimmedAndLowercased() {
        assertThat(AllowPolicy.normalizeEmail("  Nome.Cognome@Example.COM ")).isEqualTo("nome.cognome@example.com");
        assertThat(AllowPolicy.normalizeEmail(null)).isEmpty();
    }

    @Test
    void domainsLoseTheLeadingAtSign() {
        assertThat(AllowPolicy.normalizeDomain(" @Example.com ")).isEqualTo("example.com");
    }

    @Test
    void theDomainOfAnEmailIsWhatFollowsTheLastAtSignNeverASuffix() {
        assertThat(AllowPolicy.domainOf("a@example.com")).isEqualTo("example.com");
        assertThat(AllowPolicy.domainOf("a@evilexample.com")).isNotEqualTo("example.com");
        assertThat(AllowPolicy.domainOf("\"a@b\"@example.com")).isEqualTo("example.com");
        assertThat(AllowPolicy.domainOf("noat")).isEmpty();
    }

    @Test
    void obviouslyMalformedEmailsAndDomainsAreRejected() {
        assertThat(AllowPolicy.isEmail("a@example.com")).isTrue();
        assertThat(AllowPolicy.isEmail("a@@example.com")).isFalse();
        assertThat(AllowPolicy.isEmail("a b@example.com")).isFalse();
        assertThat(AllowPolicy.isEmail("a@localhost")).isFalse();
        assertThat(AllowPolicy.isEmail("")).isFalse();
        assertThat(AllowPolicy.isDomain("example.com")).isTrue();
        assertThat(AllowPolicy.isDomain("a@example.com")).isFalse();
        assertThat(AllowPolicy.isDomain("-example.com")).isFalse();
        assertThat(AllowPolicy.isDomain("*.example.com")).isFalse();
    }
}
