package com.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "agent.python")
public class AgentPythonProperties {

    private boolean enabled = false;
    private String baseUrl = "http://127.0.0.1:8000";
    private String sharedSecret = "dev-agent-secret";
    private int signatureMaxClockSkewSeconds = 300;
    private int connectTimeoutSeconds = 10;
    private int requestTimeoutSeconds = 120;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getSharedSecret() {
        return sharedSecret;
    }

    public void setSharedSecret(String sharedSecret) {
        this.sharedSecret = sharedSecret;
    }

    public int getSignatureMaxClockSkewSeconds() {
        return signatureMaxClockSkewSeconds;
    }

    public void setSignatureMaxClockSkewSeconds(int signatureMaxClockSkewSeconds) {
        this.signatureMaxClockSkewSeconds = signatureMaxClockSkewSeconds;
    }

    public int getConnectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
        this.connectTimeoutSeconds = connectTimeoutSeconds;
    }

    public int getRequestTimeoutSeconds() {
        return requestTimeoutSeconds;
    }

    public void setRequestTimeoutSeconds(int requestTimeoutSeconds) {
        this.requestTimeoutSeconds = requestTimeoutSeconds;
    }
}

