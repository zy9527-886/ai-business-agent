package com.example.service;

import cn.hutool.core.date.DateUtil;
import com.example.common.enums.ResultCodeEnum;
import com.example.config.PgVectorConfig;
import com.example.entity.KnowledgeSegment;
import com.example.entity.KnowledgeSource;
import com.example.exception.CustomException;
import jakarta.annotation.Resource;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class PgVectorService {

    private final JdbcTemplate jdbcTemplate;

    public PgVectorService(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @Resource
    private PgVectorConfig pgVectorConfig;

    public void saveSegmentVector(KnowledgeSegment segment, List<Double> embedding) {
        String sql = "insert into " + pgVectorConfig.getTableName() + " " +
                "(segment_id, knowledge_base_id, document_id, content, embedding, create_time, update_time) " +
                "values (?, ?, ?, ?, ?::agent.vector, ?, ?) " +
                "on conflict (segment_id) do update set " +
                "knowledge_base_id = excluded.knowledge_base_id, " +
                "document_id = excluded.document_id, " +
                "content = excluded.content, " +
                "embedding = excluded.embedding, " +
                "update_time = excluded.update_time";
        String now = DateUtil.now();
        try {
            jdbcTemplate.update(sql, segment.getId(), segment.getKnowledgeBaseId(), segment.getDocumentId(),
                    segment.getContent(), toVectorLiteral(embedding), now, now);
        } catch (Exception e) {
            throw new CustomException(ResultCodeEnum.SYSTEM_ERROR.code, "写入 PGVector 失败：" + e.getMessage());
        }
    }

    public void deleteBySegmentId(Integer segmentId) {
        String sql = "delete from " + pgVectorConfig.getTableName() + " where segment_id = ?";
        try {
            jdbcTemplate.update(sql, segmentId);
        } catch (Exception e) {
            throw new CustomException(ResultCodeEnum.SYSTEM_ERROR.code, "删除 PGVector 片段向量失败：" + e.getMessage());
        }
    }

    public void deleteByDocumentId(Integer documentId) {
        String sql = "delete from " + pgVectorConfig.getTableName() + " where document_id = ?";
        try {
            jdbcTemplate.update(sql, documentId);
        } catch (Exception e) {
            throw new CustomException(ResultCodeEnum.SYSTEM_ERROR.code, "删除 PGVector 文档向量失败：" + e.getMessage());
        }
    }

    public void deleteByKnowledgeBaseId(Integer knowledgeBaseId) {
        String sql = "delete from " + pgVectorConfig.getTableName() + " where knowledge_base_id = ?";
        try {
            jdbcTemplate.update(sql, knowledgeBaseId);
        } catch (Exception e) {
            throw new CustomException(ResultCodeEnum.SYSTEM_ERROR.code, "删除 PGVector 知识库向量失败：" + e.getMessage());
        }
    }

    public List<KnowledgeSource> searchSimilar(List<Double> embedding, Integer topK, Double similarityThreshold) {
        String sql = "select segment_id, knowledge_base_id, document_id, content, " +
                "(1 - (embedding <=> ?::agent.vector)) as similarity " +
                "from " + pgVectorConfig.getTableName() + " " +
                "where (1 - (embedding <=> ?::agent.vector)) >= ? " +
                "order by embedding <=> ?::agent.vector " +
                "limit ?";
        String vector = toVectorLiteral(embedding);
        try {
            return jdbcTemplate.query(sql, (resultSet, rowNum) -> {
                KnowledgeSource source = new KnowledgeSource();
                source.setSegmentId(resultSet.getInt("segment_id"));
                source.setKnowledgeBaseId(resultSet.getInt("knowledge_base_id"));
                source.setDocumentId(resultSet.getInt("document_id"));
                source.setContent(resultSet.getString("content"));
                source.setSimilarity(resultSet.getDouble("similarity"));
                return source;
            }, vector, vector, similarityThreshold, vector, topK);
        } catch (Exception e) {
            throw new CustomException(ResultCodeEnum.SYSTEM_ERROR.code, "PGVector 相似度检索失败：" + e.getMessage());
        }
    }

    @PostConstruct
    public void initTable() {
        if (!pgVectorConfig.getTableName().matches("[a-z_][a-z0-9_]*")
                || pgVectorConfig.getDimension() == null || pgVectorConfig.getDimension() <= 0) {
            throw new IllegalArgumentException("Invalid pgvector table name or dimension");
        }
        String sql = "create table if not exists " + pgVectorConfig.getTableName() + " (" +
                "id bigserial primary key, " +
                "segment_id int unique not null, " +
                "knowledge_base_id int, " +
                "document_id int, " +
                "content text, " +
                "embedding agent.vector(" + pgVectorConfig.getDimension() + "), " +
                "create_time varchar(255), " +
                "update_time varchar(255)" +
                ")";
        String indexSql = "create index if not exists idx_" + pgVectorConfig.getTableName() + "_embedding " +
                "on " + pgVectorConfig.getTableName() + " using ivfflat (embedding agent.vector_cosine_ops) with (lists = 100)";
        try {
            jdbcTemplate.execute(sql);
            jdbcTemplate.execute(indexSql);
        } catch (Exception e) {
            throw new IllegalStateException("初始化 PGVector 表失败：" + e.getMessage(), e);
        }
    }

    private String toVectorLiteral(List<Double> embedding) {
        return "[" + embedding.stream().map(String::valueOf).collect(Collectors.joining(",")) + "]";
    }
}
