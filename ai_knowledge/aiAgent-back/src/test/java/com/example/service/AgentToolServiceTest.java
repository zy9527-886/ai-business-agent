package com.example.service;

import com.example.config.PgVectorConfig;
import com.example.entity.KnowledgeDocument;
import com.example.entity.KnowledgeSource;
import com.example.entity.agent.AgentEvidence;
import com.example.entity.agent.VectorSearchRequest;
import com.example.exception.CustomException;
import com.example.mapper.KnowledgeDocumentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AgentToolServiceTest {

    @Mock
    private PgVectorService pgVectorService;
    @Mock
    private KnowledgeDocumentMapper knowledgeDocumentMapper;
    private AgentToolService service;

    @BeforeEach
    void setUp() {
        PgVectorConfig config = new PgVectorConfig();
        config.setDimension(3);
        service = new AgentToolService(pgVectorService, knowledgeDocumentMapper, config);
    }

    @Test
    void rejectsWrongVectorDimensionBeforeSearch() {
        VectorSearchRequest request = request(List.of(1.0, 2.0));
        assertThrows(CustomException.class, () -> service.searchKnowledge(request));
        verifyNoInteractions(pgVectorService);
    }

    @Test
    void convertsSearchResultsToKnowledgeEvidenceAndFillsTitle() {
        KnowledgeSource source = new KnowledgeSource();
        source.setSegmentId(11);
        source.setKnowledgeBaseId(2);
        source.setDocumentId(7);
        source.setContent("退款应在七天内申请");
        source.setSimilarity(0.91);
        KnowledgeDocument document = new KnowledgeDocument();
        document.setTitle("售后制度");
        when(pgVectorService.searchSimilar(anyList(), eq(5), eq(0.2))).thenReturn(List.of(source));
        when(knowledgeDocumentMapper.selectById(7)).thenReturn(document);

        List<AgentEvidence> result = service.searchKnowledge(request(List.of(1.0, 2.0, 3.0)));

        assertEquals(1, result.size());
        assertEquals("knowledge", result.getFirst().getSourceType());
        assertEquals("segment:11", result.getFirst().getSourceId());
        assertEquals("售后制度", result.getFirst().getTitle());
        assertFalse(result.getFirst().isDemo());
    }

    @Test
    void demoToolsAreReadOnlyExplicitlyMarkedEvidence() {
        AgentEvidence order = service.queryDemoOrder("DEMO-1001");
        AgentEvidence ticket = service.queryDemoTicket("TICKET-2001");
        AgentEvidence inventory = service.queryDemoInventory("SKU-3001");

        assertAll(
                () -> assertTrue(order.isDemo()),
                () -> assertTrue(ticket.isDemo()),
                () -> assertTrue(inventory.isDemo()),
                () -> assertEquals("business", order.getSourceType()),
                () -> assertTrue(order.getContent().contains("演示数据"))
        );
        assertThrows(CustomException.class, () -> service.queryDemoOrder("UNKNOWN"));
    }

    private VectorSearchRequest request(List<Double> vector) {
        VectorSearchRequest request = new VectorSearchRequest();
        request.setQueryVector(vector);
        request.setTopK(5);
        request.setSimilarityThreshold(0.2);
        request.setKnowledgeBaseIds(Collections.emptyList());
        return request;
    }
}
