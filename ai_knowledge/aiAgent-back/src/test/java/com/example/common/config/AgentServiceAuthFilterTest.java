package com.example.common.config;

import com.example.config.AgentPythonProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AgentServiceAuthFilterTest {

    @Test
    void acceptsValidSignatureOncePreservesBodyAndRejectsReplay() throws Exception {
        AgentPythonProperties properties = properties();
        AgentServiceAuthFilter filter = new AgentServiceAuthFilter(properties, () -> 1000L);
        MockHttpServletRequest request = request("1000", "nonce-1", "{}");
        request.addHeader("X-Signature", sign(properties.getSharedSecret(), request, "{}"));
        AtomicReference<String> downstreamBody = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> downstreamBody.set(new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8)));

        assertEquals("{}", downstreamBody.get());
        MockHttpServletResponse replayResponse = new MockHttpServletResponse();
        filter.doFilter(request, replayResponse, noOpChain());
        assertEquals(401, replayResponse.getStatus());
    }

    @Test
    void rejectsStaleAndTamperedRequestsBeforeController() throws Exception {
        AgentPythonProperties properties = properties();
        AgentServiceAuthFilter filter = new AgentServiceAuthFilter(properties, () -> 1000L);
        AtomicBoolean reached = new AtomicBoolean();
        FilterChain chain = (req, res) -> reached.set(true);

        MockHttpServletRequest stale = request("900", "nonce-2", "{}");
        stale.addHeader("X-Signature", sign(properties.getSharedSecret(), stale, "{}"));
        MockHttpServletResponse staleResponse = new MockHttpServletResponse();
        filter.doFilter(stale, staleResponse, chain);
        assertEquals(401, staleResponse.getStatus());

        MockHttpServletRequest tampered = request("1000", "nonce-3", "{\"changed\":true}");
        tampered.addHeader("X-Signature", sign(properties.getSharedSecret(), tampered, "{}"));
        MockHttpServletResponse tamperedResponse = new MockHttpServletResponse();
        filter.doFilter(tampered, tamperedResponse, chain);
        assertEquals(401, tamperedResponse.getStatus());
        assertFalse(reached.get());
    }

    private AgentPythonProperties properties() {
        AgentPythonProperties properties = new AgentPythonProperties();
        properties.setSharedSecret("test-secret");
        properties.setSignatureMaxClockSkewSeconds(60);
        return properties;
    }

    private MockHttpServletRequest request(String timestamp, String nonce, String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/agent/v1/knowledge/search");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        request.addHeader("X-Timestamp", timestamp);
        request.addHeader("X-Nonce", nonce);
        return request;
    }

    private FilterChain noOpChain() {
        return (request, response) -> fail("request must not reach controller");
    }

    private String sign(String secret, MockHttpServletRequest request, String body) throws Exception {
        String bodyHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(body.getBytes(StandardCharsets.UTF_8)));
        String canonical = String.join("\n", request.getMethod(), request.getRequestURI(),
                request.getHeader("X-Timestamp"), request.getHeader("X-Nonce"), bodyHash);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
    }
}
