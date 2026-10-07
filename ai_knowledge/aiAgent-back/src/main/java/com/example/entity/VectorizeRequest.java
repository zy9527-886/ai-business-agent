package com.example.entity;

import java.util.List;

public class VectorizeRequest {

    /** Python 模型配置名 */
    private String profile = "default-embedding";
    /** 知识片段ID集合 */
    private List<Integer> ids;

    public String getProfile() {
        return profile;
    }

    public void setProfile(String profile) {
        this.profile = profile;
    }

    public List<Integer> getIds() {
        return ids;
    }

    public void setIds(List<Integer> ids) {
        this.ids = ids;
    }
}
