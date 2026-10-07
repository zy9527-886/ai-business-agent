package com.example.client;

import com.example.config.AgentPythonProperties;
import com.example.entity.agent.PythonChatRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class PythonAgentClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void sendsSignedRequestAndStreamsNamedEvents() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicReference<String> signature = new AtomicReference<>();
        AtomicReference<JsonNode> body = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/chat/stream", exchange -> {
            signature.set(exchange.getRequestHeaders().getFirst("X-Signature"));
            body.set(mapper.readTree(exchange.getRequestBody()));
            byte[] response = "event: status\ndata: {\"phase\":\"routing\"}\n\n"
                    .concat("event: message\ndata: hello\n\n")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        AgentPythonProperties properties = new AgentPythonProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setSharedSecret("test-secret");
        PythonAgentClient client = new PythonAgentClient(properties, mapper);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> message = new AtomicReference<>();

        client.stream(request(), event -> {
            if ("message".equals(event.name())) message.set(event.data());
        }, done::countDown, error -> done.countDown());

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertNotNull(signature.get());
        assertEquals("question", body.get().get("question").asText());
        assertEquals("hello", message.get());
    }

    private PythonChatRequest request() {
        return new PythonChatRequest(
                "r1", "c1", "question",
                new PythonChatRequest.UserContext(1, "USER", List.of()),
                new PythonChatRequest.Options(
                        "default-chat", "default-embedding", 5, 0.2,
                        List.of("knowledge", "business", "web"))
        );
    }
}
