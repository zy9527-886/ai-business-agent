package com.example.entity.agent;

import java.util.LinkedHashMap;
import java.util.Map;

public class AgentEvidence {
    private String sourceType;
    private String sourceId;
    private String title;
    private String content;
    private String url;
    private Double score;
    private String observedAt;
    private boolean demo;
    private Map<String, Object> metadata = new LinkedHashMap<>();

    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public String getSourceId() { return sourceId; }
    public void setSourceId(String sourceId) { this.sourceId = sourceId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public Double getScore() { return score; }
    public void setScore(Double score) { this.score = score; }
    public String getObservedAt() { return observedAt; }
    public void setObservedAt(String observedAt) { this.observedAt = observedAt; }
    public boolean isDemo() { return demo; }
    public void setDemo(boolean demo) { this.demo = demo; }
    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
}

