package com.example.client;

import com.example.common.enums.ResultCodeEnum;
import com.example.config.AgentPythonProperties;
import com.example.entity.agent.EmbeddingRequest;
import com.example.entity.agent.EmbeddingResponse;
import com.example.exception.CustomException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

@Component
public class PythonEmbeddingClient {

    private static final String EMBEDDINGS_PATH = "/api/v1/embeddings";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final AgentPythonProperties properties;
    private final LongSupplier epochSeconds;
    private final Supplier<String> nonceSupplier;

    public PythonEmbeddingClient(ObjectMapper objectMapper, AgentPythonProperties properties) {
        this(configuredBuilder(properties), objectMapper, properties,
                () -> Instant.now().getEpochSecond(), () -> UUID.randomUUID().toString());
    }

    PythonEmbeddingClient(RestClient.Builder builder, ObjectMapper objectMapper,
                          AgentPythonProperties properties, LongSupplier epochSeconds,
                          Supplier<String> nonceSupplier) {
        this.restClient = builder.baseUrl(properties.getBaseUrl()).build();
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.epochSeconds = epochSeconds;
        this.nonceSupplier = nonceSupplier;
    }

    private static RestClient.Builder configuredBuilder(AgentPythonProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(properties.getConnectTimeoutSeconds()));
        requestFactory.setReadTimeout(Duration.ofSeconds(properties.getRequestTimeoutSeconds()));
        return RestClient.builder().requestFactory(requestFactory);
    }

    public EmbeddingResponse embed(String profile, List<String> texts) {
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(new EmbeddingRequest(profile, texts));
        } catch (JsonProcessingException exception) {
            throw new CustomException(ResultCodeEnum.SYSTEM_ERROR.code, "序列化向量请求失败");
        }
        String timestamp = String.valueOf(epochSeconds.getAsLong());
        String nonce = nonceSupplier.get();
        String signature = sign("POST", EMBEDDINGS_PATH, timestamp, nonce, body);
        try {
            EmbeddingResponse response = restClient.post()
                    .uri(EMBEDDINGS_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Timestamp", timestamp)
                    .header("X-Nonce", nonce)
                    .header("X-Signature", signature)
                    .body(body)
                    .retrieve()
                    .body(EmbeddingResponse.class);
            if (response == null) {
                throw new CustomException(ResultCodeEnum.SYSTEM_ERROR.code, "Python 向量服务返回空响应");
            }
            return response;
        } catch (CustomException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw new CustomException(ResultCodeEnum.SYSTEM_ERROR.code,
                    "调用 Python 向量服务失败：" + exception.getMessage());
        }
    }

    private String sign(String method, String path, String timestamp, String nonce, byte[] body) {
        try {
            String bodyHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            String canonical = String.join("\n", method, path, timestamp, nonce, bodyHash);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.getSharedSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new CustomException(ResultCodeEnum.SYSTEM_ERROR.code, "签名 Python 服务请求失败");
        }
    }
}
