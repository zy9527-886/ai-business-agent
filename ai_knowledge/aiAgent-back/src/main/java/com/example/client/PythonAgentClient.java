package com.example.client;

import com.example.config.AgentPythonProperties;
import com.example.entity.agent.PythonChatRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Consumer;

@Component
public class PythonAgentClient {
    private static final String CHAT_PATH = "/api/v1/chat/stream";

    private final AgentPythonProperties properties;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;
    private final RestClient restClient;

    public PythonAgentClient(AgentPythonProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.webClient = WebClient.builder().baseUrl(properties.getBaseUrl()).build();
        this.restClient = RestClient.builder().baseUrl(properties.getBaseUrl()).build();
    }

    public boolean isAvailable() {
        try {
            return restClient.get().uri("/health/ready").retrieve()
                    .toBodilessEntity().getStatusCode().is2xxSuccessful();
        } catch (Exception ignored) {
            return false;
        }
    }

    public Disposable stream(PythonChatRequest request,
                             Consumer<StreamEvent> onEvent,
                             Runnable onComplete,
                             Consumer<Throwable> onError) {
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(request);
        } catch (JsonProcessingException exception) {
            onError.accept(exception);
            return () -> { };
        }
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String nonce = UUID.randomUUID().toString();
        String signature = sign("POST", CHAT_PATH, timestamp, nonce, body);
        return webClient.post()
                .uri(CHAT_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .header("X-Timestamp", timestamp)
                .header("X-Nonce", nonce)
                .header("X-Signature", signature)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .timeout(Duration.ofSeconds(properties.getRequestTimeoutSeconds()))
                .subscribe(
                        event -> onEvent.accept(new StreamEvent(
                                event.event() == null ? "message" : event.event(), event.data())),
                        onError,
                        onComplete
                );
    }

    private String sign(String method, String path, String timestamp, String nonce, byte[] body) {
        try {
            String bodyHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            String canonical = String.join("\n", method, path, timestamp, nonce, bodyHash);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.getSharedSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("cannot sign Python request", exception);
        }
    }

    public record StreamEvent(String name, String data) {}
}
