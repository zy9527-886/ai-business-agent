package com.example.service;

import cn.hutool.core.util.StrUtil;
import com.example.common.enums.ResultCodeEnum;
import com.example.config.PgVectorConfig;
import com.example.entity.AiModelConfig;
import com.example.exception.CustomException;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class EmbeddingService {

    @Resource
    private PgVectorConfig pgVectorConfig;

    // 按 ai_model_config 表里的配置构建 Spring AI 的 EmbeddingModel
    @Resource
    private SpringAiModelFactory springAiModelFactory;

    /**
     * 把一段文本向量化成向量。知识片段入库和用户问题检索都调用它。
     *
     * EmbeddingModel 是 Spring AI 的向量入口：拼 OpenAI 兼容的 /embeddings 报文、带 Authorization 头、
     * 从响应里取 data[0].embedding，这些都由它完成，这里只负责给出配置、维度和文本。
     * Spring AI 返回的是 float[]，这里转成下游 PgVector 存储和检索使用的 List&lt;Double&gt;。
     */
    public List<Double> embed(AiModelConfig modelConfig, String text) {
        validateModelConfig(modelConfig);
        if (StrUtil.isBlank(text)) {
            throw new CustomException(ResultCodeEnum.PARAM_ERROR.code, "知识片段内容不能为空");
        }
        try {
            // 维度取自 pgvector.dimension，作为 dimensions 参数下发，保证返回维度和向量表一致
            float[] vector = springAiModelFactory.getEmbeddingModel(modelConfig, pgVectorConfig.getDimension()).embed(text);
            List<Double> result = new ArrayList<>(vector.length);
            for (float value : vector) {
                result.add((double) value);
            }
            // 返回维度必须和向量表列宽一致，否则存进 PgVector 会报错，这里提前拦下来
            if (!result.isEmpty() && result.size() != pgVectorConfig.getDimension()) {
                throw new CustomException(ResultCodeEnum.SYSTEM_ERROR.code, "向量维度不匹配，当前返回维度：" + result.size());
            }
            return result;
        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            throw new CustomException(ResultCodeEnum.SYSTEM_ERROR.code, "向量模型调用异常：" + e.getMessage());
        }
    }

    private void validateModelConfig(AiModelConfig modelConfig) {
        if (modelConfig == null) {
            throw new CustomException(ResultCodeEnum.PARAM_ERROR.code, "请选择向量模型配置");
        }
        if (!"向量模型".equals(modelConfig.getModelType())) {
            throw new CustomException(ResultCodeEnum.PARAM_ERROR.code, "请选择向量模型类型的配置");
        }
        if (!"是".equals(modelConfig.getEnabled())) {
            throw new CustomException(ResultCodeEnum.PARAM_ERROR.code, "当前向量模型配置未启用");
        }
        if (StrUtil.hasBlank(modelConfig.getBaseUrl(), modelConfig.getApiKey(), modelConfig.getModelName())) {
            throw new CustomException(ResultCodeEnum.PARAM_ERROR.code, "向量模型配置不完整");
        }
        if (modelConfig.getApiKey().contains("请替换")) {
            throw new CustomException(ResultCodeEnum.PARAM_ERROR.code, "请先在模型配置中填写真实 API Key");
        }
    }
}
