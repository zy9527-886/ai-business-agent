package com.example.controller;

import com.example.entity.KnowledgeChatRequest;
import com.example.service.MultiAgentChatService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/knowledgeChat")
public class KnowledgeChatController {

    private final MultiAgentChatService multiAgentChatService;

    public KnowledgeChatController(MultiAgentChatService multiAgentChatService) {
        this.multiAgentChatService = multiAgentChatService;
    }

    /**
     * 管理端流式问答（SSE）
     */
    @PostMapping(value = "/askStream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter askStream(@RequestBody KnowledgeChatRequest request) {
        return multiAgentChatService.chatStream(request, false);
    }

    /**
     * 用户端流式问答（SSE）
     */
    @PostMapping(value = "/frontAskStream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter frontAskStream(@RequestBody KnowledgeChatRequest request) {
        return multiAgentChatService.chatStream(request, true);
    }
}
