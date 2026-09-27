package com.example.service;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.example.common.enums.ResultCodeEnum;
import com.example.entity.AiModelConfig;
import com.example.entity.KnowledgeDocument;
import com.example.entity.KnowledgeChatRequest;
import com.example.entity.KnowledgeSource;
import com.example.exception.CustomException;
import com.example.mapper.AiModelConfigMapper;
import com.example.mapper.KnowledgeDocumentMapper;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.model.Generation;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

@Service
public class KnowledgeChatService {

    private static final int DEFAULT_TOP_K = 5;
    private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.2;

    /** 客服问答的系统提示：只基于检索到的资料作答，不编造。 */
    private static final String SYSTEM_PROMPT =
            "你是企业智能客服助手。请严格基于给定资料回答问题；资料中没有的信息不要编造。回答要清晰、准确、适合客服场景。";

    @Resource
    private AiModelConfigMapper aiModelConfigMapper;
    @Resource
    private EmbeddingService embeddingService;
    @Resource
    private PgVectorService pgVectorService;
    @Resource
    private KnowledgeDocumentMapper knowledgeDocumentMapper;

    // 按 ai_model_config 表里的配置构建 Spring AI 的 ChatClient
    @Resource
    private SpringAiModelFactory springAiModelFactory;

    /**
     * 管理端流式问答：校验模型和参数后走 SSE 流式返回
     */
    public SseEmitter chatStream(KnowledgeChatRequest request) {
        if (request == null || StrUtil.isBlank(request.getQuestion())) {
            throw new CustomException(ResultCodeEnum.PARAM_ERROR.code, "请输入问题");
        }
        AiModelConfig embeddingModel = getModelConfig(request.getEmbeddingModelConfigId(), "向量模型");
        AiModelConfig chatModel = getModelConfig(request.getChatModelConfigId(), "聊天模型");
        Integer topK = ObjectUtil.defaultIfNull(request.getTopK(), DEFAULT_TOP_K);
        Double threshold = ObjectUtil.defaultIfNull(request.getSimilarityThreshold(), DEFAULT_SIMILARITY_THRESHOLD);
        return doChatStream(chatModel, embeddingModel, request.getQuestion(), topK, threshold);
    }

    /**
     * 用户端流式问答：用默认启用的聊天模型和向量模型，走 SSE 流式返回
     */
    public SseEmitter frontChatStream(KnowledgeChatRequest request) {
        if (request == null || StrUtil.isBlank(request.getQuestion())) {
            throw new CustomException(ResultCodeEnum.PARAM_ERROR.code, "请输入问题");
        }
        AiModelConfig chatModel = getDefaultModelConfig("聊天模型");
        AiModelConfig embeddingModel = getDefaultModelConfig("向量模型");
        return doChatStream(chatModel, embeddingModel, request.getQuestion(), DEFAULT_TOP_K, DEFAULT_SIMILARITY_THRESHOLD);
    }

    private SseEmitter doChatStream(AiModelConfig chatModel, AiModelConfig embeddingModel,
                                    String question, Integer topK, Double threshold) {
        SseEmitter emitter = new SseEmitter(120000L);
        // 检索和模型调用都放到异步线程，避免阻塞请求线程，异常统一通过 error 事件下发
        CompletableFuture.runAsync(() -> {
            try {
                // 1. 问题向量化 + 检索相似片段 + 补文档标题
                List<Double> questionVector = embeddingService.embed(embeddingModel, question);
                List<KnowledgeSource> sources = pgVectorService.searchSimilar(questionVector, topK, threshold);
                fillDocumentTitle(sources);
                // 2. 先把资料来源发给前端，右侧来源区可以立即展示
                sendSse(emitter, "sources", sources);
                // 3. 没有命中资料就不调模型，直接下发兜底话术
                if (sources.isEmpty()) {
                    sendSse(emitter, "message", "知识库中没有检索到足够相关的资料，建议补充知识文档或降低相似度阈值后再试。");
                    sendSse(emitter, "done", "");
                    emitter.complete();
                    return;
                }
                // 4. 命中资料后流式调用聊天模型，每个增量片段通过 message 事件下发
                callChatModelStream(chatModel, question, sources, content -> sendSse(emitter, "message", content));
                sendSse(emitter, "done", "");
                emitter.complete();
            } catch (Exception e) {
                sendSse(emitter, "error", e.getMessage());
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    private void sendSse(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (Exception e) {
            throw new CustomException(ResultCodeEnum.SYSTEM_ERROR.code, "流式响应发送失败：" + e.getMessage());
        }
    }

    private void fillDocumentTitle(List<KnowledgeSource> sources) {
        for (KnowledgeSource source : sources) {
            if (source.getDocumentId() == null) {
                continue;
            }
            KnowledgeDocument document = knowledgeDocumentMapper.selectById(source.getDocumentId());
            if (document != null) {
                source.setDocumentTitle(document.getTitle());
            }
        }
    }

    private AiModelConfig getModelConfig(Integer id, String modelType) {
        if (id == null) {
            throw new CustomException(ResultCodeEnum.PARAM_ERROR.code, "请选择" + modelType + "配置");
        }
        AiModelConfig modelConfig = aiModelConfigMapper.selectById(id);
        if (modelConfig == null) {
            throw new CustomException(ResultCodeEnum.PARAM_ERROR.code, modelType + "配置不存在");
        }
        if (!modelType.equals(modelConfig.getModelType())) {
            throw new CustomException(ResultCodeEnum.PARAM_ERROR.code, "请选择正确的" + modelType + "配置");
        }
        if (!"是".equals(modelConfig.getEnabled())) {
            throw new CustomException(ResultCodeEnum.PARAM_ERROR.code, modelType + "配置未启用");
        }
        if (StrUtil.hasBlank(modelConfig.getBaseUrl(), modelConfig.getApiKey(), modelConfig.getModelName())) {
            throw new CustomException(ResultCodeEnum.PARAM_ERROR.code, modelType + "配置不完整");
        }
        if (modelConfig.getApiKey().contains("请替换")) {
            throw new CustomException(ResultCodeEnum.PARAM_ERROR.code, "请先在模型配置中填写真实 API Key");
        }
        return modelConfig;
    }

    private AiModelConfig getDefaultModelConfig(String modelType) {
        AiModelConfig query = new AiModelConfig();
        query.setModelType(modelType);
        query.setEnabled("是");
        List<AiModelConfig> list = aiModelConfigMapper.selectAll(query);
        if (list.isEmpty()) {
            throw new CustomException(ResultCodeEnum.PARAM_ERROR.code, "请先配置启用的" + modelType);
        }
        return list.get(0);
    }

    /**
     * 流式调用聊天模型，模型每吐一小段就回调一次。
     *
     * ChatClient 是 Spring AI 的对话入口：拼 OpenAI 兼容报文、带 Authorization 头、
     * SSE 分帧、[DONE] 结束判断、从 delta 里取增量文本，这些都由它处理，这里只负责给出系统提示和资料 Prompt。
     * stream().chatResponse() 返回 Flux&lt;ChatResponse&gt;，外层已在异步线程里，所以用 toStream() 阻塞式逐段取，
     * 保持“取到一段就推一段”的顺序。
     */
    private void callChatModelStream(AiModelConfig chatModel, String question,
                                     List<KnowledgeSource> sources, Consumer<String> contentConsumer) {
        try {
            springAiModelFactory.getChatClient(chatModel)
                    .prompt()
                    // 系统提示约束“只基于资料作答”
                    .system(SYSTEM_PROMPT)
                    // 用户问题 + 检索到的资料拼成一条 user 消息
                    .user(buildUserPrompt(question, sources))
                    .stream()
                    .chatResponse()
                    .toStream()
                    .forEach(response -> {
                        Generation generation = response == null ? null : response.getResult();
                        // 有的服务商会在最后额外发一帧只带用量统计、不带内容，这种帧直接跳过
                        if (generation == null) {
                            return;
                        }
                        String content = generation.getOutput() == null ? null : generation.getOutput().getText();
                        // 增量片段可能是空串（例如只带角色信息的首帧），空的不用往页面推
                        if (StrUtil.isNotBlank(content)) {
                            contentConsumer.accept(content);
                        }
                    });
        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            throw new CustomException(ResultCodeEnum.SYSTEM_ERROR.code, "聊天模型流式调用异常：" + e.getMessage());
        }
    }

    private String buildUserPrompt(String question, List<KnowledgeSource> sources) {
        StringBuilder builder = new StringBuilder();
        builder.append("用户问题：\n").append(question).append("\n\n");
        builder.append("企业资料：\n");
        for (int i = 0; i < sources.size(); i++) {
            KnowledgeSource source = sources.get(i);
            builder.append("【资料").append(i + 1).append("，相似度")
                    .append(String.format("%.2f", source.getSimilarity())).append("】\n")
                    .append(source.getContent()).append("\n\n");
        }
        builder.append("请根据企业资料回答用户问题。如果资料不足，请明确说明资料不足。");
        return builder.toString();
    }
}
