package com.example.service;

import com.example.client.PythonEmbeddingClient;
import com.example.config.PgVectorConfig;
import com.example.entity.KnowledgeSegment;
import com.example.entity.agent.EmbeddingResponse;
import com.example.exception.CustomException;
import com.example.mapper.KnowledgeBaseMapper;
import com.example.mapper.KnowledgeDocumentMapper;
import com.example.mapper.KnowledgeSegmentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KnowledgeSegmentServiceTest {

    @Mock private KnowledgeSegmentMapper segmentMapper;
    @Mock private KnowledgeDocumentMapper documentMapper;
    @Mock private KnowledgeBaseMapper baseMapper;
    @Mock private PythonEmbeddingClient embeddingClient;
    @Mock private PgVectorService pgVectorService;
    private KnowledgeSegmentService service;
    private KnowledgeSegment segment;

    @BeforeEach
    void setUp() {
        PgVectorConfig vectorConfig = new PgVectorConfig();
        vectorConfig.setDimension(3);
        service = new KnowledgeSegmentService(
                segmentMapper, documentMapper, baseMapper, embeddingClient, pgVectorService, vectorConfig);
        segment = new KnowledgeSegment();
        segment.setId(9);
        segment.setDocumentId(3);
        segment.setKnowledgeBaseId(2);
        segment.setContent("退款规则");
        when(segmentMapper.selectById(9)).thenReturn(segment);
        when(segmentMapper.selectAll(any())).thenReturn(Collections.emptyList());
    }

    @Test
    void vectorizesThroughPythonAndPersistsReturnedVector() {
        EmbeddingResponse response = new EmbeddingResponse();
        response.setProfile("default-embedding");
        response.setDimension(3);
        response.setVectors(List.of(List.of(0.1, 0.2, 0.3)));
        when(embeddingClient.embed("default-embedding", List.of("退款规则"))).thenReturn(response);

        service.vectorizeById(9, "default-embedding");

        verify(pgVectorService).saveSegmentVector(segment, List.of(0.1, 0.2, 0.3));
        verify(segmentMapper).updateVectorStatus(eq(9), eq("已向量化"), anyString());
    }

    @Test
    void rejectsDimensionMismatchAndMarksSegmentFailed() {
        EmbeddingResponse response = new EmbeddingResponse();
        response.setDimension(2);
        response.setVectors(List.of(List.of(0.1, 0.2)));
        when(embeddingClient.embed("bad-profile", List.of("退款规则"))).thenReturn(response);

        CustomException exception = assertThrows(
                CustomException.class, () -> service.vectorizeById(9, "bad-profile"));

        assertEquals("向量维度不匹配：期望 3，实际 2", exception.getMsg());
        verifyNoInteractions(pgVectorService);
        verify(segmentMapper).updateVectorStatus(eq(9), eq("向量化失败"), anyString());
    }
}
