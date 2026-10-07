package com.example.service;

import com.example.client.PythonAgentClient;
import com.example.config.AgentPythonProperties;
import com.example.entity.KnowledgeChatRequest;
import com.example.entity.User;
import com.example.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MultiAgentChatServiceTest {
    @Mock private PythonAgentClient pythonClient;
    @Mock private KnowledgeChatService legacyService;
    @Mock private CurrentUserProvider currentUserProvider;
    private AgentPythonProperties properties;
    private KnowledgeChatRequest request;

    @BeforeEach
    void setUp() {
        properties = new AgentPythonProperties();
        request = new KnowledgeChatRequest();
        request.setQuestion("退款规则");
        User user = new User();
        user.setId(7);
        user.setRole("USER");
        lenient().when(currentUserProvider.current()).thenReturn(user);
    }

    @Test
    void disabledFlagUsesLegacyService() {
        properties.setEnabled(false);
        SseEmitter fallback = new SseEmitter();
        when(legacyService.frontChatStream(request)).thenReturn(fallback);
        MultiAgentChatService service = service();
        assertSame(fallback, service.chatStream(request, false));
        verifyNoInteractions(pythonClient);
    }

    @Test
    void unavailablePythonUsesLegacyFallback() {
        properties.setEnabled(true);
        when(pythonClient.isAvailable()).thenReturn(false);
        SseEmitter fallback = new SseEmitter();
        when(legacyService.frontChatStream(request)).thenReturn(fallback);
        assertSame(fallback, service().chatStream(request, true));
    }

    @Test
    void enabledPythonMapsUserAndStartsStreaming() {
        properties.setEnabled(true);
        when(pythonClient.isAvailable()).thenReturn(true);
        SseEmitter result = service().chatStream(request, false);
        verify(pythonClient).stream(any(), any(), any(), any());
        verifyNoInteractions(legacyService);
        result.complete();
    }

    private MultiAgentChatService service() {
        return new MultiAgentChatService(
                pythonClient, legacyService, currentUserProvider, properties);
    }
}
