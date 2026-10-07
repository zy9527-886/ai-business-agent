package com.example.service;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.example.client.PythonEmbeddingClient;
import com.example.common.enums.ResultCodeEnum;
import com.example.config.PgVectorConfig;
import com.example.entity.KnowledgeDocument;
import com.example.entity.KnowledgeSegment;
import com.example.entity.KnowledgeBase;
import com.example.entity.agent.EmbeddingResponse;
import com.example.exception.CustomException;
import com.example.mapper.KnowledgeBaseMapper;
import com.example.mapper.KnowledgeDocumentMapper;
import com.example.mapper.KnowledgeSegmentMapper;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class KnowledgeSegmentService {

    private static final int MAX_CHUNK_LENGTH = 500;
    private static final int OVERLAP_LENGTH = 80;

    private final KnowledgeSegmentMapper knowledgeSegmentMapper;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final PythonEmbeddingClient embeddingClient;
    private final PgVectorService pgVectorService;
    private final PgVectorConfig pgVectorConfig;

    public KnowledgeSegmentService(KnowledgeSegmentMapper knowledgeSegmentMapper,
                                   KnowledgeDocumentMapper knowledgeDocumentMapper,
                                   KnowledgeBaseMapper knowledgeBaseMapper,
                                   PythonEmbeddingClient embeddingClient,
                                   PgVectorService pgVectorService,
                                   PgVectorConfig pgVectorConfig) {
        this.knowledgeSegmentMapper = knowledgeSegmentMapper;
        this.knowledgeDocumentMapper = knowledgeDocumentMapper;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.embeddingClient = embeddingClient;
        this.pgVectorService = pgVectorService;
        this.pgVectorConfig = pgVectorConfig;
    }

    @Transactional
    public void deleteById(Integer id) {
        KnowledgeSegment segment = knowledgeSegmentMapper.selectById(id);
        pgVectorService.deleteBySegmentId(id);
        knowledgeSegmentMapper.deleteById(id);
        if (ObjectUtil.isNotNull(segment)) {
            refreshDocumentVectorStatus(segment.getDocumentId());
        }
    }

    @Transactional
    public void deleteBatch(List<Integer> ids) {
        for (Integer id : ids) {
            deleteById(id);
        }
    }

    public PageInfo<KnowledgeSegment> selectPage(KnowledgeSegment knowledgeSegment, Integer pageNum, Integer pageSize) {
        PageHelper.startPage(pageNum, pageSize);
        List<KnowledgeSegment> list = knowledgeSegmentMapper.selectAll(knowledgeSegment);
        return PageInfo.of(list);
    }

    public void vectorizeById(Integer id, String profile) {
        KnowledgeSegment segment = knowledgeSegmentMapper.selectById(id);
        if (ObjectUtil.isNull(segment)) {
            throw new CustomException(ResultCodeEnum.PARAM_ERROR.code, "知识片段不存在");
        }
        if (StrUtil.isBlank(segment.getContent())) {
            throw new CustomException(ResultCodeEnum.PARAM_ERROR.code, "知识片段内容为空");
        }

        try {
            if (StrUtil.isBlank(profile)) {
                throw new CustomException(ResultCodeEnum.PARAM_ERROR.code, "请选择向量模型配置");
            }
            EmbeddingResponse response = embeddingClient.embed(profile, List.of(segment.getContent()));
            List<Double> vector = extractSingleVector(response);
            pgVectorService.saveSegmentVector(segment, vector);
            knowledgeSegmentMapper.updateVectorStatus(id, "已向量化", DateUtil.now());
            // 片段状态变了，同步刷新所属文档的整体向量化状态
            refreshDocumentVectorStatus(segment.getDocumentId());
        } catch (CustomException e) {
            knowledgeSegmentMapper.updateVectorStatus(id, "向量化失败", DateUtil.now());
            refreshDocumentVectorStatus(segment.getDocumentId());
            throw e;
        } catch (Exception e) {
            knowledgeSegmentMapper.updateVectorStatus(id, "向量化失败", DateUtil.now());
            refreshDocumentVectorStatus(segment.getDocumentId());
            throw new CustomException(ResultCodeEnum.SYSTEM_ERROR.code, "知识片段向量化失败：" + e.getMessage());
        }
    }

    public void vectorizeBatch(List<Integer> ids, String profile) {
        if (ids == null || ids.isEmpty()) {
            throw new CustomException(ResultCodeEnum.PARAM_ERROR.code, "请选择知识片段");
        }
        for (Integer id : ids) {
            vectorizeById(id, profile);
        }
    }

    @Transactional
    public List<KnowledgeSegment> generateByDocumentId(Integer documentId) {
        KnowledgeDocument document = knowledgeDocumentMapper.selectById(documentId);
        if (ObjectUtil.isNull(document)) {
            throw new CustomException(ResultCodeEnum.PARAM_ERROR.code, "文档不存在");
        }
        if (!"已解析".equals(document.getParseStatus()) || StrUtil.isBlank(document.getParsedText())) {
            throw new CustomException(ResultCodeEnum.PARAM_ERROR.code, "请先完成文档解析");
        }

        KnowledgeBase knowledgeBase = knowledgeBaseMapper.selectById(document.getKnowledgeBaseId());
        int chunkSize = ObjectUtil.defaultIfNull(knowledgeBase == null ? null : knowledgeBase.getChunkSize(), MAX_CHUNK_LENGTH);
        int overlapSize = ObjectUtil.defaultIfNull(knowledgeBase == null ? null : knowledgeBase.getOverlapSize(), OVERLAP_LENGTH);

        List<String> chunkList = splitText(document.getParsedText(), chunkSize, overlapSize);
        if (chunkList.isEmpty()) {
            throw new CustomException(ResultCodeEnum.PARAM_ERROR.code, "没有可生成片段的文本内容");
        }

        pgVectorService.deleteByDocumentId(documentId);
        knowledgeSegmentMapper.deleteByDocumentId(documentId);

        String now = DateUtil.now();
        List<KnowledgeSegment> segmentList = new ArrayList<>();
        for (int i = 0; i < chunkList.size(); i++) {
            String content = chunkList.get(i);
            KnowledgeSegment segment = new KnowledgeSegment();
            segment.setKnowledgeBaseId(document.getKnowledgeBaseId());
            segment.setDocumentId(document.getId());
            segment.setSegmentIndex(i + 1);
            segment.setContent(content);
            segment.setCharCount(content.length());
            segment.setVectorStatus("未向量化");
            segment.setCreateTime(now);
            segment.setUpdateTime(now);
            knowledgeSegmentMapper.insert(segment);
            segmentList.add(segment);
        }
        // 重新切片后旧向量已删、新片段都是未向量化，同步把文档整体状态重置为未向量化
        knowledgeDocumentMapper.updateVectorStatus(documentId, "未向量化", now);
        return segmentList;
    }

    /**
     * 根据某个文档下所有片段的向量化情况，回写文档级 vector_status：
     * 无片段 / 存在未向量化 -> 未向量化；存在失败 -> 向量化失败；全部已向量化 -> 已向量化
     */
    private void refreshDocumentVectorStatus(Integer documentId) {
        if (ObjectUtil.isNull(documentId)) {
            return;
        }
        KnowledgeSegment query = new KnowledgeSegment();
        query.setDocumentId(documentId);
        List<KnowledgeSegment> segments = knowledgeSegmentMapper.selectAll(query);
        String status;
        if (segments.isEmpty()) {
            status = "未向量化";
        } else if (segments.stream().anyMatch(item -> "向量化失败".equals(item.getVectorStatus()))) {
            status = "向量化失败";
        } else if (segments.stream().allMatch(item -> "已向量化".equals(item.getVectorStatus()))) {
            status = "已向量化";
        } else {
            status = "未向量化";
        }
        knowledgeDocumentMapper.updateVectorStatus(documentId, status, DateUtil.now());
    }

    private List<Double> extractSingleVector(EmbeddingResponse response) {
        if (response.getDimension() == null || response.getVectors() == null
                || response.getVectors().size() != 1 || response.getVectors().getFirst() == null) {
            throw new CustomException(ResultCodeEnum.SYSTEM_ERROR.code, "Python 向量服务返回格式错误");
        }
        List<Double> vector = response.getVectors().getFirst();
        int expected = pgVectorConfig.getDimension();
        if (response.getDimension() != expected || vector.size() != expected) {
            throw new CustomException(ResultCodeEnum.PARAM_ERROR.code,
                    "向量维度不匹配：期望 " + expected + "，实际 " + response.getDimension());
        }
        return vector;
    }

    private List<String> splitText(String text, int chunkSize, int overlapSize) {
        String normalizedText = text.replace("\r\n", "\n")
                .replace("\r", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
        String[] paragraphs = normalizedText.split("\\n\\s*\\n");
        List<String> chunkList = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String paragraph : paragraphs) {
            String value = paragraph.trim();
            if (StrUtil.isBlank(value)) {
                continue;
            }
            if (value.length() > chunkSize) {
                flushChunk(chunkList, current);
                splitLongParagraph(chunkList, value, chunkSize, overlapSize);
                continue;
            }
            if (current.length() > 0 && current.length() + value.length() + 2 > chunkSize) {
                flushChunk(chunkList, current);
            }
            if (current.length() > 0) {
                current.append("\n\n");
            }
            current.append(value);
        }

        flushChunk(chunkList, current);
        return chunkList;
    }

    private void splitLongParagraph(List<String> chunkList, String paragraph, int chunkSize, int overlapSize) {
        int start = 0;
        while (start < paragraph.length()) {
            int end = Math.min(start + chunkSize, paragraph.length());
            String chunk = paragraph.substring(start, end).trim();
            if (StrUtil.isNotBlank(chunk)) {
                chunkList.add(chunk);
            }
            if (end >= paragraph.length()) {
                break;
            }
            start = Math.max(end - overlapSize, start + 1);
        }
    }

    private void flushChunk(List<String> chunkList, StringBuilder current) {
        if (current.length() == 0) {
            return;
        }
        String chunk = current.toString().trim();
        if (StrUtil.isNotBlank(chunk)) {
            chunkList.add(chunk);
        }
        current.setLength(0);
    }
}
