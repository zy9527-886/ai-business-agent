package com.example.service;

import cn.hutool.core.util.StrUtil;
import com.example.common.enums.ResultCodeEnum;
import com.example.entity.AiModelConfig;
import com.example.exception.CustomException;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按数据库里的模型配置构建 Spring AI 的 ChatClient 和 EmbeddingModel。
 *
 * 知识库有两类 AI 调用：聊天模型（RAG 问答）和向量模型（片段 Embedding），
 * 两者的接口地址、Key、模型名都存在 ai_model_config 表里，管理员在后台改完要立刻生效、不能重启，
 * 所以这里不用 Spring AI 的自动配置 Bean，而是自己按配置构建，并把构建结果缓存起来复用。
 */
@Component
public class SpringAiModelFactory {

    /**
     * 聊天模型统一用较低的温度：企业客服问答要基于资料稳定作答、不发散。
     * ai_model_config 表里没有温度字段，这个值由后端固定。
     */
    private static final Double CHAT_TEMPERATURE = 0.2;

    /** 建立 TCP 连接的超时。地址填错或服务商不可达时，不让请求一直挂着。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);

    /**
     * 等模型返回的超时，作用于同步调用（片段向量化 embed）。
     * 流式聊天走的是 WebClient，不受这个读取超时限制，所以一次完整问答可以持续更久。
     */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

    // 缓存已构建的 ChatClient / EmbeddingModel，key 由配置内容拼成，配置变了 key 就变，自然拿到新实例
    private final Map<String, ChatClient> chatClientCache = new ConcurrentHashMap<>();
    private final Map<String, EmbeddingModel> embeddingModelCache = new ConcurrentHashMap<>();

    /**
     * 取（或构建）聊天用的 ChatClient，供知识库问答走 stream() 流式返回。
     *
     * @param config 后台【AI模型配置】里选中的“聊天模型”配置
     */
    public ChatClient getChatClient(AiModelConfig config) {
        validate(config);
        return chatClientCache.computeIfAbsent(cacheKey(config),
                key -> ChatClient.create(buildChatModel(config)));
    }

    /**
     * 取（或构建）向量化用的 EmbeddingModel，供片段和问题走 embed() 得到向量。
     *
     * @param config    后台【AI模型配置】里选中的“向量模型”配置
     * @param dimension 期望的向量维度，来自 pgvector.dimension，请求时作为 dimensions 参数下发
     */
    public EmbeddingModel getEmbeddingModel(AiModelConfig config, Integer dimension) {
        validate(config);
        return embeddingModelCache.computeIfAbsent(cacheKey(config) + "|" + dimension,
                key -> buildEmbeddingModel(config, dimension));
    }

    private OpenAiChatModel buildChatModel(AiModelConfig config) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                // 模型名来自配置里的 model_name，例如 deepseek-chat
                .model(config.getModelName())
                .temperature(CHAT_TEMPERATURE)
                .build();
        return OpenAiChatModel.builder()
                // 聊天走 /chat/completions
                .openAiApi(buildApi(config, "/chat/completions"))
                .defaultOptions(options)
                .build();
    }

    private OpenAiEmbeddingModel buildEmbeddingModel(AiModelConfig config, Integer dimension) {
        OpenAiEmbeddingOptions.Builder optionsBuilder = OpenAiEmbeddingOptions.builder()
                // 模型名来自配置里的 model_name，例如 text-embedding-v3
                .model(config.getModelName());
        // 维度只在配置了正数时下发，避免个别服务商不支持 dimensions 参数时报错
        if (dimension != null && dimension > 0) {
            optionsBuilder.dimensions(dimension);
        }
        // 向量走 /embeddings；MetadataMode.EMBED 表示只把正文交给向量模型
        return new OpenAiEmbeddingModel(buildApi(config, "/embeddings"), MetadataMode.EMBED, optionsBuilder.build());
    }

    /**
     * 构造 OpenAiApi。
     *
     * 表里的 base_url 允许管理员写三种形式：只写服务商根地址（https://api.deepseek.com）、
     * 写到版本号（https://api.deepseek.com/v1）、或者写完整的聊天/向量地址。
     * OpenAiApi 要的是“主机”和“路径”分开的形式，所以这里先按 URI 拆开再补齐路径，
     * 保证三种写法最终请求到的都是同一个地址。endpointSuffix 区分聊天(/chat/completions)和向量(/embeddings)。
     */
    private OpenAiApi buildApi(AiModelConfig config, String endpointSuffix) {
        String rawUrl = config.getBaseUrl().trim();
        URI uri = URI.create(rawUrl);

        // 没写协议头的地址（比如直接填域名）拆不出主机，直接当作主机用，路径按默认补
        if (StrUtil.isBlank(uri.getScheme()) || StrUtil.isBlank(uri.getAuthority())) {
            return apiBuilder(config, StrUtil.removeSuffix(rawUrl, "/"), endpointSuffix, endpointSuffix);
        }

        // 协议 + 主机作为 baseUrl，例如 https://api.deepseek.com
        String baseUrl = uri.getScheme() + "://" + uri.getAuthority();
        // 剩下的部分作为路径前缀，例如 ""、"/v1"、"/compatible-mode/v1"
        String path = StrUtil.removeSuffix(StrUtil.blankToDefault(uri.getPath(), ""), "/");
        // 管理员已经写了完整地址就直接用，否则在路径前缀后面补上聊天/向量后缀
        String endpointPath = path.endsWith(endpointSuffix) ? path : path + endpointSuffix;
        return apiBuilder(config, baseUrl, endpointPath, endpointSuffix);
    }

    /**
     * 按“聊天/向量”把 endpointPath 塞进对应的 path，再套上底层 HTTP 客户端和超时。
     */
    private OpenAiApi apiBuilder(AiModelConfig config, String baseUrl, String endpointPath, String endpointSuffix) {
        OpenAiApi.Builder builder = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(config.getApiKey())
                .restClientBuilder(buildRestClientBuilder());
        if ("/embeddings".equals(endpointSuffix)) {
            builder.embeddingsPath(endpointPath);
        } else {
            builder.completionsPath(endpointPath);
        }
        return builder.build();
    }

    /**
     * 构建同步调用用的底层 HTTP 客户端，并套上连接/读取超时。
     * 只作用于 RestClient，也就是 embed() 走的同步链路；stream() 走 WebClient，不受读取超时限制。
     */
    private RestClient.Builder buildRestClientBuilder() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder().requestFactory(requestFactory);
    }

    /**
     * 缓存 key 用配置内容拼，而不是用主键 ID。
     * 这样管理员改了 Key 或模型名之后 key 会变，下次调用自动构建新实例，不会命中旧配置。
     */
    private String cacheKey(AiModelConfig config) {
        return config.getBaseUrl() + "|" + config.getApiKey() + "|" + config.getModelName();
    }

    private void validate(AiModelConfig config) {
        if (config == null
                || StrUtil.hasBlank(config.getBaseUrl(), config.getApiKey(), config.getModelName())) {
            throw new CustomException(ResultCodeEnum.PARAM_ERROR.code, "模型配置不完整，请先补充接口地址、API Key 和模型名称");
        }
    }
}
