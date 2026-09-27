package com.example;

import com.example.entity.*;
import com.example.mapper.*;
import com.example.service.KnowledgeBaseService;
import com.example.service.PgVectorService;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL + pgvector tests. Test records are rolled back or cleaned up. */
@SpringBootTest(properties = {"spring.ai.model.chat=none", "spring.ai.model.embedding=none"})
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
@Transactional
class PostgresIntegrationTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("TEST_DB_URL"));
        properties.add("spring.datasource.username", () -> System.getenv("DB_USERNAME"));
        properties.add("spring.datasource.password", () -> System.getenv("DB_PASSWORD"));
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        properties.add("spring.datasource.hikari.schema", () -> "agent");
    }

    @Autowired UserMapper users;
    @Autowired AiModelConfigMapper models;
    @Autowired KnowledgeBaseMapper bases;
    @Autowired KnowledgeDocumentMapper documents;
    @Autowired KnowledgeSegmentMapper segments;
    @Autowired KnowledgeBaseService baseService;
    @Autowired PgVectorService vectors;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void userCrudGeneratedKeyAndPagination() {
        User user = new User();
        user.setUsername("pg-test-" + UUID.randomUUID());
        user.setPassword("test-only");
        user.setName("迁移测试");
        user.setRole("USER");
        users.insert(user);
        assertNotNull(user.getId());
        assertEquals(user.getId(), users.selectByUsername(user.getUsername()).getId());
        user.setName("修改后的用户");
        users.updateById(user);
        assertEquals(user.getName(), users.selectById(user.getId()).getName());
        User filter = new User();
        filter.setName("修改后的用户");
        PageInfo<User> page;
        try {
            PageHelper.startPage(1, 1);
            page = PageInfo.of(users.selectAll(filter));
        } finally {
            PageHelper.clearPage();
        }
        assertEquals(1, page.getTotal());
        assertEquals(user.getId(), page.getList().getFirst().getId());
        users.deleteById(user.getId());
        assertNull(users.selectById(user.getId()));
    }

    @Test
    void modelCrudAndFilters() {
        AiModelConfig model = new AiModelConfig();
        model.setName("pg-model-" + UUID.randomUUID());
        model.setModelType("聊天模型");
        model.setEnabled("否");
        models.insert(model);
        assertNotNull(model.getId());
        model.setRemark("PostgreSQL 中文更新");
        models.updateById(model);
        assertEquals(model.getRemark(), models.selectById(model.getId()).getRemark());
        AiModelConfig filter = new AiModelConfig();
        filter.setName(model.getName());
        filter.setEnabled("否");
        assertEquals(1, models.selectAll(filter).size());
        models.deleteById(model.getId());
        assertNull(models.selectById(model.getId()));
    }

    private KnowledgeSegment fixture() {
        KnowledgeBase base = new KnowledgeBase();
        base.setName("迁移测试知识库");
        base.setCode("pg-test-" + UUID.randomUUID());
        base.setStatus("启用");
        bases.insert(base);
        assertNotNull(base.getId());
        assertEquals(base.getId(), bases.selectByCode(base.getCode()).getId());
        base.setDescription("测试中文与换行\n第二行");
        bases.updateById(base);
        assertEquals(base.getDescription(), bases.selectById(base.getId()).getDescription());
        KnowledgeBase baseFilter = new KnowledgeBase();
        baseFilter.setCode(base.getCode());
        assertEquals(1, bases.selectAll(baseFilter).size());

        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setKnowledgeBaseId(base.getId());
        doc.setTitle("迁移测试文档");
        doc.setParsedText("中文正文\n第二段");
        documents.insert(doc);
        assertNotNull(doc.getId());
        doc.setTitle("更新文档");
        documents.updateById(doc);
        documents.updateVectorStatus(doc.getId(), "已向量化", "2026-09-26 00:00:00");
        assertEquals("已向量化", documents.selectById(doc.getId()).getVectorStatus());
        KnowledgeDocument docFilter = new KnowledgeDocument();
        docFilter.setKnowledgeBaseId(base.getId());
        docFilter.setTitle("更新");
        assertEquals(base.getName(), documents.selectAll(docFilter).getFirst().getKnowledgeBaseName());
        documents.resetProcessById(doc.getId(), "2026-09-26 00:00:00");
        assertNull(documents.selectById(doc.getId()).getParsedText());

        KnowledgeSegment segment = new KnowledgeSegment();
        segment.setKnowledgeBaseId(base.getId());
        segment.setDocumentId(doc.getId());
        segment.setSegmentIndex(1);
        segment.setContent("迁移测试片段");
        segments.insert(segment);
        assertNotNull(segment.getId());
        segments.updateVectorStatus(segment.getId(), "已向量化", "2026-09-26 00:00:00");
        assertEquals("已向量化", segments.selectById(segment.getId()).getVectorStatus());
        KnowledgeSegment filter = new KnowledgeSegment();
        filter.setDocumentId(doc.getId());
        filter.setContent("迁移测试");
        assertEquals("更新文档", segments.selectAll(filter).getFirst().getDocumentTitle());
        return segment;
    }

    @Test
    void vectorUpsertSimilarityAndCascadeDelete() {
        KnowledgeSegment segment = fixture();
        var embedding = Collections.nCopies(1024, 1.0);
        vectors.saveSegmentVector(segment, embedding);
        segment.setContent("已更新的向量正文");
        vectors.saveSegmentVector(segment, embedding);
        assertEquals(1, jdbc.queryForObject("select count(*) from knowledge_segment_vector where segment_id=?", Integer.class, segment.getId()));
        var matches = vectors.searchSimilar(embedding, 5, 0.999);
        assertTrue(matches.stream().anyMatch(source -> source.getSegmentId().equals(segment.getId()) && source.getContent().equals(segment.getContent())));
        TransactionTemplate nested = new TransactionTemplate(transactionManager);
        nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        nested.executeWithoutResult(status -> {
            baseService.deleteById(segment.getKnowledgeBaseId());
            status.setRollbackOnly();
        });
        assertEquals(1, jdbc.queryForObject("select count(*) from knowledge_segment where id=?", Integer.class, segment.getId()));
        assertEquals(1, jdbc.queryForObject("select count(*) from knowledge_segment_vector where segment_id=?", Integer.class, segment.getId()));
        baseService.deleteById(segment.getKnowledgeBaseId());
        assertNull(bases.selectById(segment.getKnowledgeBaseId()));
        assertNull(documents.selectById(segment.getDocumentId()));
        assertNull(segments.selectById(segment.getId()));
        assertEquals(0, jdbc.queryForObject("select count(*) from knowledge_segment_vector where segment_id=?", Integer.class, segment.getId()));
    }

    @Test
    void individualDocumentAndSegmentDeletes() {
        KnowledgeSegment segment = fixture();
        var embedding = Collections.nCopies(1024, 1.0);
        vectors.saveSegmentVector(segment, embedding);
        vectors.deleteBySegmentId(segment.getId());
        assertEquals(0, jdbc.queryForObject("select count(*) from knowledge_segment_vector where segment_id=?", Integer.class, segment.getId()));
        vectors.saveSegmentVector(segment, embedding);
        vectors.deleteByDocumentId(segment.getDocumentId());
        segments.deleteById(segment.getId());
        documents.deleteById(segment.getDocumentId());
        bases.deleteById(segment.getKnowledgeBaseId());
        assertNull(segments.selectById(segment.getId()));
        assertNull(documents.selectById(segment.getDocumentId()));
        assertEquals(0, jdbc.queryForObject("select count(*) from knowledge_segment_vector where segment_id=?", Integer.class, segment.getId()));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void vectorAndBusinessWritesRollbackTogether() {
        Integer[] ids = new Integer[2];
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            KnowledgeSegment segment = fixture();
            ids[0] = segment.getId();
            ids[1] = segment.getKnowledgeBaseId();
            vectors.saveSegmentVector(segment, Collections.nCopies(1024, 1.0));
            assertEquals(1, jdbc.queryForObject("select count(*) from knowledge_segment_vector where segment_id=?", Integer.class, ids[0]));
            status.setRollbackOnly();
        });
        assertNull(segments.selectById(ids[0]));
        assertNull(bases.selectById(ids[1]));
        assertEquals(0, jdbc.queryForObject("select count(*) from knowledge_segment_vector where segment_id=?", Integer.class, ids[0]));
    }

}
