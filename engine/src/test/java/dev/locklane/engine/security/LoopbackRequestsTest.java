package dev.locklane.engine.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class LoopbackRequestsTest {
    // Session ids ("<projectId>-console[-<hex>]"), "<repo>-console-<hex>" worktree directories and the
    // /console and /consoles REST paths below keep their persisted and on-the-wire shape: compatibility
    // surfaces kept under ADR-112 (#766 renamed only the identifiers).

    @Test
    void aLoopbackPeerWithNoForwardingHeaderIsLocal() {
        assertThat(LoopbackRequests.isDirectLoopback(from("127.0.0.1"))).isTrue();
        assertThat(LoopbackRequests.isDirectLoopback(from("127.0.0.53"))).isTrue();
        assertThat(LoopbackRequests.isDirectLoopback(from("0:0:0:0:0:0:0:1"))).isTrue();
        assertThat(LoopbackRequests.isDirectLoopback(from("::1"))).isTrue();
    }

    @Test
    void aNonLoopbackPeerIsNotLocal() {
        assertThat(LoopbackRequests.isDirectLoopback(from("192.168.1.20"))).isFalse();
        assertThat(LoopbackRequests.isDirectLoopback(from("10.0.0.1"))).isFalse();
        assertThat(LoopbackRequests.isDirectLoopback(from("fe80:0:0:0:0:0:0:1"))).isFalse();
        assertThat(LoopbackRequests.isDirectLoopback(from("2001:db8::1"))).isFalse();
    }

    @Test
    void aLoopbackPeerRelayedByAProxyIsNotLocal() {
        MockHttpServletRequest xForwardedFor = from("127.0.0.1");
        xForwardedFor.addHeader("X-Forwarded-For", "203.0.113.7");
        assertThat(LoopbackRequests.isDirectLoopback(xForwardedFor)).isFalse();

        // Even when the header claims the browser is local, its presence means a relay
        // is in the path, so the claim is not trusted.
        MockHttpServletRequest claimsLocal = from("127.0.0.1");
        claimsLocal.addHeader("X-Forwarded-For", "127.0.0.1");
        assertThat(LoopbackRequests.isDirectLoopback(claimsLocal)).isFalse();

        MockHttpServletRequest forwarded = from("::1");
        forwarded.addHeader("Forwarded", "for=203.0.113.7;proto=https");
        assertThat(LoopbackRequests.isDirectLoopback(forwarded)).isFalse();
    }

    @Test
    void anythingThatIsNotAnIpLiteralIsNotLocalAndIsNeverResolved() {
        assertThat(LoopbackRequests.isLoopbackLiteral("localhost")).isFalse();
        assertThat(LoopbackRequests.isLoopbackLiteral("loopback.example")).isFalse();
        assertThat(LoopbackRequests.isLoopbackLiteral("")).isFalse();
        assertThat(LoopbackRequests.isLoopbackLiteral(null)).isFalse();
        assertThat(LoopbackRequests.isLoopbackLiteral("::1%lo")).isFalse();
    }

    private static MockHttpServletRequest from(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/projects/1/consoles/1-x/open-ide");
        request.setRemoteAddr(remoteAddr);
        return request;
    }
}
