package com.example.entity.agent;

import java.util.List;

public class EmbeddingResponse {

    private String profile;
    private Integer dimension;
    private List<List<Double>> vectors;

    public String getProfile() {
        return profile;
    }

    public void setProfile(String profile) {
        this.profile = profile;
    }

    public Integer getDimension() {
        return dimension;
    }

    public void setDimension(Integer dimension) {
        this.dimension = dimension;
    }

    public List<List<Double>> getVectors() {
        return vectors;
    }

    public void setVectors(List<List<Double>> vectors) {
        this.vectors = vectors;
    }
}
