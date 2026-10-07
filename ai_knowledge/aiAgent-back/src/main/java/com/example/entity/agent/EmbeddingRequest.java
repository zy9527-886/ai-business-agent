package com.example.entity.agent;

import java.util.List;

public class EmbeddingRequest {

    private String profile;
    private List<String> texts;

    public EmbeddingRequest() {
    }

    public EmbeddingRequest(String profile, List<String> texts) {
        this.profile = profile;
        this.texts = texts;
    }

    public String getProfile() {
        return profile;
    }

    public void setProfile(String profile) {
        this.profile = profile;
    }

    public List<String> getTexts() {
        return texts;
    }

    public void setTexts(List<String> texts) {
        this.texts = texts;
    }
}
