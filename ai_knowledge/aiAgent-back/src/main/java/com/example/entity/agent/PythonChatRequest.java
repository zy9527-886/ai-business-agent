package com.example.entity.agent;

import java.util.List;

public record PythonChatRequest(
        String requestId,
        String conversationId,
        String question,
        UserContext user,
        Options options
) {
    public record UserContext(Integer userId, String role, List<String> permissions) {}

    public record Options(
            String chatModelProfile,
            String embeddingModelProfile,
            Integer topK,
            Double similarityThreshold,
            List<String> enabledAgents
    ) {}
}
