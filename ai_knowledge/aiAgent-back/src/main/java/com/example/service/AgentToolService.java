package com.example.service;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.ObjectUtil;
import com.example.common.enums.ResultCodeEnum;
import com.example.config.PgVectorConfig;
import com.example.entity.KnowledgeDocument;
import com.example.entity.KnowledgeSource;
import com.example.entity.agent.AgentEvidence;
import com.example.entity.agent.VectorSearchRequest;
import com.example.exception.CustomException;
import com.example.mapper.KnowledgeDocumentMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AgentToolService {

    private final PgVectorService pgVectorService;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final PgVectorConfig pgVectorConfig;

    public AgentToolService(PgVectorService pgVectorService, KnowledgeDocumentMapper knowledgeDocumentMapper,
                            PgVectorConfig pgVectorConfig) {
        this.pgVectorService = pgVectorService;
        this.knowledgeDocumentMapper = knowledgeDocumentMapper;
        this.pgVectorConfig = pgVectorConfig;
    }

    public List<AgentEvidence> searchKnowledge(VectorSearchRequest request) {
        if (request == null || request.getQueryVector() == null
                || request.getQueryVector().size() != pgVectorConfig.getDimension()) {
            throw new CustomException(ResultCodeEnum.PARAM_ERROR.code,
                    "查询向量维度必须为" + pgVectorConfig.getDimension());
        }
        int topK = ObjectUtil.defaultIfNull(request.getTopK(), 5);
        double threshold = ObjectUtil.defaultIfNull(request.getSimilarityThreshold(), 0.2);
        if (topK < 1 || topK > 20 || threshold < 0 || threshold > 1) {
            throw new CustomException(ResultCodeEnum.PARAM_ERROR.code, "检索参数超出允许范围");
        }
        return pgVectorService.searchSimilar(request.getQueryVector(), topK, threshold).stream()
                .filter(source -> request.getKnowledgeBaseIds() == null || request.getKnowledgeBaseIds().isEmpty()
                        || request.getKnowledgeBaseIds().contains(source.getKnowledgeBaseId()))
                .map(this::toEvidence)
                .toList();
    }

    private AgentEvidence toEvidence(KnowledgeSource source) {
        AgentEvidence evidence = new AgentEvidence();
        evidence.setSourceType("knowledge");
        evidence.setSourceId("segment:" + source.getSegmentId());
        KnowledgeDocument document = source.getDocumentId() == null ? null
                : knowledgeDocumentMapper.selectById(source.getDocumentId());
        evidence.setTitle(document == null ? "知识片段" : document.getTitle());
        evidence.setContent(source.getContent());
        evidence.setScore(source.getSimilarity());
        evidence.setObservedAt(DateUtil.now());
        evidence.setDemo(false);
        evidence.setMetadata(Map.of(
                "segmentId", source.getSegmentId(),
                "knowledgeBaseId", source.getKnowledgeBaseId(),
                "documentId", source.getDocumentId()
        ));
        return evidence;
    }

    public AgentEvidence queryDemoOrder(String orderNo) {
        Map<String, String> values = Map.of(
                "DEMO-1001", "演示数据：订单 DEMO-1001 已发货，承运商为顺丰，预计两日内送达。",
                "DEMO-1002", "演示数据：订单 DEMO-1002 等待付款。"
        );
        return demo("order", orderNo, "演示订单", values.get(orderNo));
    }

    public AgentEvidence queryDemoTicket(String ticketNo) {
        Map<String, String> values = Map.of(
                "TICKET-2001", "演示数据：售后工单 TICKET-2001 正在处理中，优先级为高。",
                "TICKET-2002", "演示数据：售后工单 TICKET-2002 已关闭。"
        );
        return demo("ticket", ticketNo, "演示售后工单", values.get(ticketNo));
    }

    public AgentEvidence queryDemoInventory(String sku) {
        Map<String, String> values = Map.of(
                "SKU-3001", "演示数据：SKU-3001 可用库存 128 件。",
                "SKU-3002", "演示数据：SKU-3002 当前缺货。"
        );
        return demo("inventory", sku, "演示库存", values.get(sku));
    }

    private AgentEvidence demo(String kind, String id, String title, String content) {
        if (content == null) {
            throw new CustomException(ResultCodeEnum.PARAM_ERROR.code, "DEMO_DATA_NOT_FOUND");
        }
        AgentEvidence evidence = new AgentEvidence();
        evidence.setSourceType("business");
        evidence.setSourceId(kind + ":" + id);
        evidence.setTitle(title);
        evidence.setContent(content);
        evidence.setObservedAt(DateUtil.now());
        evidence.setDemo(true);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "java-demo-fixture");
        metadata.put("kind", kind);
        evidence.setMetadata(metadata);
        return evidence;
    }
}

