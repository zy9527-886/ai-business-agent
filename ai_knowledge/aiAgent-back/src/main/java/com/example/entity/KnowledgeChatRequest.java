package com.example.entity;

import java.util.List;

public class KnowledgeChatRequest {

    /** 用户问题 */
    private String question;
    /** 聊天模型配置ID */
    private Integer chatModelConfigId;
    /** 向量模型配置ID */
    private Integer embeddingModelConfigId;
    /** 召回片段数量 */
    private Integer topK;
    /** 相似度阈值 */
    private Double similarityThreshold;
    private String requestId;
    private String conversationId;
    private String chatModelProfile;
    private String embeddingModelProfile;
    private List<String> enabledAgents;

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public Integer getChatModelConfigId() {
        return chatModelConfigId;
    }

    public void setChatModelConfigId(Integer chatModelConfigId) {
        this.chatModelConfigId = chatModelConfigId;
    }

    public Integer getEmbeddingModelConfigId() {
        return embeddingModelConfigId;
    }

    public void setEmbeddingModelConfigId(Integer embeddingModelConfigId) {
        this.embeddingModelConfigId = embeddingModelConfigId;
    }

    public Integer getTopK() {
        return topK;
    }

    public void setTopK(Integer topK) {
        this.topK = topK;
    }

    public Double getSimilarityThreshold() {
        return similarityThreshold;
    }

    public void setSimilarityThreshold(Double similarityThreshold) {
        this.similarityThreshold = similarityThreshold;
    }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public String getChatModelProfile() { return chatModelProfile; }
    public void setChatModelProfile(String chatModelProfile) { this.chatModelProfile = chatModelProfile; }
    public String getEmbeddingModelProfile() { return embeddingModelProfile; }
    public void setEmbeddingModelProfile(String embeddingModelProfile) { this.embeddingModelProfile = embeddingModelProfile; }
    public List<String> getEnabledAgents() { return enabledAgents; }
    public void setEnabledAgents(List<String> enabledAgents) { this.enabledAgents = enabledAgents; }
}
