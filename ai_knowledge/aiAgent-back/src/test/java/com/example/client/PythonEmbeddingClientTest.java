package com.example.client;

import com.example.config.AgentPythonProperties;
import com.example.entity.agent.EmbeddingResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PythonEmbeddingClientTest {

    @Test
    void signsRequestAndParsesEmbeddingResponse() {
        AgentPythonProperties properties = new AgentPythonProperties();
        properties.setBaseUrl("http://agent-python.test");
        properties.setSharedSecret("test-secret");
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PythonEmbeddingClient client = new PythonEmbeddingClient(
                builder, new ObjectMapper(), properties, () -> 1_700_000_000L, () -> "nonce-1");

        server.expect(requestTo("http://agent-python.test/api/v1/embeddings"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"profile\":\"default-embedding\",\"texts\":[\"hello\"]}"))
                .andExpect(header("X-Timestamp", "1700000000"))
                .andExpect(header("X-Nonce", "nonce-1"))
                .andExpect(header("X-Signature", "eceb8d12d8d6626f7fda6a68992aa695b88599f09f7dab6ce9c9751cc5da0f82"))
                .andRespond(withSuccess(
                        "{\"profile\":\"default-embedding\",\"dimension\":3,\"vectors\":[[0.1,0.2,0.3]]}",
                        MediaType.APPLICATION_JSON));

        EmbeddingResponse response = client.embed("default-embedding", List.of("hello"));

        assertEquals(3, response.getDimension());
        assertEquals(List.of(0.1, 0.2, 0.3), response.getVectors().getFirst());
        server.verify();
    }
}
