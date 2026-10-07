package com.example.service;

import cn.hutool.core.util.StrUtil;
import com.example.client.PythonAgentClient;
import com.example.common.enums.ResultCodeEnum;
import com.example.config.AgentPythonProperties;
import com.example.entity.KnowledgeChatRequest;
import com.example.entity.User;
import com.example.entity.agent.PythonChatRequest;
import com.example.exception.CustomException;
import com.example.security.CurrentUserProvider;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class MultiAgentChatService {
    private static final int DEFAULT_TOP_K = 5;
    private static final double DEFAULT_THRESHOLD = 0.2;

    private final PythonAgentClient pythonClient;
    private final KnowledgeChatService legacyService;
    private final CurrentUserProvider currentUserProvider;
    private final AgentPythonProperties properties;

    public MultiAgentChatService(PythonAgentClient pythonClient,
                                 KnowledgeChatService legacyService,
                                 CurrentUserProvider currentUserProvider,
                                 AgentPythonProperties properties) {
        this.pythonClient = pythonClient;
        this.legacyService = legacyService;
        this.currentUserProvider = currentUserProvider;
        this.properties = properties;
    }

    public SseEmitter chatStream(KnowledgeChatRequest request, boolean front) {
        if (!properties.isEnabled() || !pythonClient.isAvailable()) {
            return front ? legacyService.frontChatStream(request) : legacyService.chatStream(request);
        }
        if (request == null || StrUtil.isBlank(request.getQuestion())) {
            throw new CustomException(ResultCodeEnum.PARAM_ERROR.code, "请输入问题");
        }
        User user = currentUserProvider.current();
        if (user == null || user.getId() == null) {
            throw new CustomException(ResultCodeEnum.USER_NOT_LOGIN);
        }
        String requestId = StrUtil.blankToDefault(request.getRequestId(), UUID.randomUUID().toString());
        String conversationId = StrUtil.blankToDefault(request.getConversationId(), UUID.randomUUID().toString());
        PythonChatRequest pythonRequest = new PythonChatRequest(
                requestId,
                conversationId,
                request.getQuestion().trim(),
                new PythonChatRequest.UserContext(user.getId(), user.getRole(), List.of()),
                new PythonChatRequest.Options(
                        StrUtil.blankToDefault(request.getChatModelProfile(), "default-chat"),
                        StrUtil.blankToDefault(request.getEmbeddingModelProfile(), "default-embedding"),
                        request.getTopK() == null ? DEFAULT_TOP_K : request.getTopK(),
                        request.getSimilarityThreshold() == null ? DEFAULT_THRESHOLD : request.getSimilarityThreshold(),
                        request.getEnabledAgents() == null || request.getEnabledAgents().isEmpty()
                                ? List.of("knowledge", "business", "web") : request.getEnabledAgents()
                )
        );
        SseEmitter emitter = new SseEmitter(properties.getRequestTimeoutSeconds() * 1000L);
        AtomicReference<Disposable> subscription = new AtomicReference<>();
        Disposable disposable = pythonClient.stream(
                pythonRequest,
                event -> send(emitter, event),
                emitter::complete,
                error -> {
                    send(emitter, new PythonAgentClient.StreamEvent(
                            "error", "多智能体服务暂时不可用，请稍后重试。"));
                    emitter.complete();
                }
        );
        subscription.set(disposable);
        Runnable cancel = () -> {
            Disposable current = subscription.get();
            if (current != null && !current.isDisposed()) current.dispose();
        };
        emitter.onCompletion(cancel);
        emitter.onTimeout(cancel);
        emitter.onError(error -> cancel.run());
        return emitter;
    }

    private void send(SseEmitter emitter, PythonAgentClient.StreamEvent event) {
        try {
            emitter.send(SseEmitter.event().name(event.name()).data(
                    event.data() == null ? "" : event.data()));
        } catch (IOException exception) {
            emitter.completeWithError(exception);
        }
    }
}
