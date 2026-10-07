package com.example.entity.agent;

import java.util.List;

public class VectorSearchRequest {
    private List<Double> queryVector;
    private Integer topK;
    private Double similarityThreshold;
    private List<Integer> knowledgeBaseIds;

    public List<Double> getQueryVector() {
        return queryVector;
    }

    public void setQueryVector(List<Double> queryVector) {
        this.queryVector = queryVector;
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

    public List<Integer> getKnowledgeBaseIds() {
        return knowledgeBaseIds;
    }

    public void setKnowledgeBaseIds(List<Integer> knowledgeBaseIds) {
        this.knowledgeBaseIds = knowledgeBaseIds;
    }
}

