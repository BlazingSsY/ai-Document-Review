package com.aireview.repository;

import com.aireview.entity.RagDocumentBlock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

@Slf4j
@Repository
@RequiredArgsConstructor
public class DocumentVectorRepository {

    private static final int MAX_VECTOR_DIMENSIONS = 16_000;
    private static final int MAX_VECTOR_HNSW_DIMENSIONS = 2_000;
    private static final int MAX_HALFVEC_HNSW_DIMENSIONS = 4_000;

    private final JdbcTemplate jdbcTemplate;

    public void deleteByTaskId(String taskId) {
        jdbcTemplate.update("DELETE FROM rag_document_blocks WHERE task_id = ?", taskId);
    }

    public void saveAll(List<RagDocumentBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) return;

        String sql = """
                INSERT INTO rag_document_blocks (
                    task_id, block_id, block_type, chapter_index, block_index,
                    section_path, text_content, text_hash, embedding_model,
                    embedding, embedding_dimension, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::vector, ?, ?)
                ON CONFLICT (task_id, block_id) DO UPDATE SET
                    block_type = EXCLUDED.block_type,
                    chapter_index = EXCLUDED.chapter_index,
                    block_index = EXCLUDED.block_index,
                    section_path = EXCLUDED.section_path,
                    text_content = EXCLUDED.text_content,
                    text_hash = EXCLUDED.text_hash,
                    embedding_model = EXCLUDED.embedding_model,
                    embedding = EXCLUDED.embedding,
                    embedding_dimension = EXCLUDED.embedding_dimension
                """;

        jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                RagDocumentBlock block = blocks.get(i);
                validateDimension(block.getEmbeddingDimension());
                ps.setString(1, block.getTaskId());
                ps.setString(2, block.getBlockId());
                ps.setString(3, block.getBlockType());
                ps.setInt(4, block.getChapterIndex());
                ps.setInt(5, block.getBlockIndex());
                ps.setString(6, block.getSectionPath());
                ps.setString(7, block.getTextContent());
                ps.setString(8, block.getTextHash());
                ps.setString(9, block.getEmbeddingModel());
                ps.setString(10, block.getEmbeddingVector());
                ps.setInt(11, block.getEmbeddingDimension());
                ps.setTimestamp(12, Timestamp.valueOf(
                        block.getCreatedAt() != null ? block.getCreatedAt() : LocalDateTime.now()));
            }

            @Override
            public int getBatchSize() {
                return blocks.size();
            }
        });
    }

    /**
     * Creates one partial HNSW index per embedding model and dimension. This avoids
     * comparing vectors produced by different models even when their dimensions match.
     */
    @Transactional
    public String ensureHnswIndex(String embeddingModel, int dimension) {
        validateDimension(dimension);
        String model = requireModel(embeddingModel);
        String strategy = indexStrategy(dimension);
        String indexName = "idx_rag_doc_blocks_hnsw_" + strategy + "_" + dimension + "_" + shortHash(model);
        String modelLiteral = model.replace("'", "''");
        String expression;
        String operatorClass;

        if (dimension <= MAX_VECTOR_HNSW_DIMENSIONS) {
            expression = "embedding::vector(" + dimension + ")";
            operatorClass = "vector_cosine_ops";
        } else if (dimension <= MAX_HALFVEC_HNSW_DIMENSIONS) {
            expression = "embedding::halfvec(" + dimension + ")";
            operatorClass = "halfvec_cosine_ops";
        } else {
            expression = "binary_quantize(embedding)::bit(" + dimension + ")";
            operatorClass = "bit_hamming_ops";
        }

        String sql = "CREATE INDEX IF NOT EXISTS " + indexName
                + " ON rag_document_blocks USING hnsw ((" + expression + ") " + operatorClass + ")"
                + " WHERE embedding IS NOT NULL"
                + " AND embedding_dimension = " + dimension
                + " AND embedding_model = '" + modelLiteral + "'";
        jdbcTemplate.execute("SELECT pg_advisory_xact_lock(hashtext('" + indexName + "'))");
        jdbcTemplate.execute(sql);
        log.info("pgvector HNSW index ready: index={}, model={}, dimension={}, strategy={}",
                indexName, model, dimension, strategy);
        return strategy;
    }

    @Transactional(readOnly = true)
    public List<ScoredRagDocumentBlock> findNearest(String taskId,
                                                 String embeddingModel,
                                                 List<Double> queryVector,
                                                 int limit,
                                                 int hnswEfSearch,
                                                 int binaryCandidateMultiplier) {
        if (queryVector == null || queryVector.isEmpty()) return List.of();
        int dimension = queryVector.size();
        validateDimension(dimension);
        String model = requireModel(embeddingModel);
        int safeLimit = Math.max(1, limit);
        int safeEfSearch = Math.max(1, Math.min(hnswEfSearch, 10_000));

        jdbcTemplate.execute("SET LOCAL hnsw.iterative_scan = strict_order");
        jdbcTemplate.execute("SET LOCAL hnsw.ef_search = " + safeEfSearch);

        String vector = toVectorLiteral(queryVector);
        if (dimension <= MAX_VECTOR_HNSW_DIMENSIONS) {
            return queryCosine(taskId, model, vector, dimension, safeLimit, false);
        }
        if (dimension <= MAX_HALFVEC_HNSW_DIMENSIONS) {
            return queryCosine(taskId, model, vector, dimension, safeLimit, true);
        }
        return queryBinaryQuantized(taskId, model, vector, dimension, safeLimit,
                Math.max(2, binaryCandidateMultiplier));
    }

    private List<ScoredRagDocumentBlock> queryCosine(String taskId,
                                                   String model,
                                                   String queryVector,
                                                   int dimension,
                                                   int limit,
                                                   boolean halfPrecision) {
        String type = halfPrecision ? "halfvec" : "vector";
        String expression = "embedding::" + type + "(" + dimension + ")";
        String queryCast = "?::" + type + "(" + dimension + ")";
        String modelLiteral = model.replace("'", "''");
        String sql = """
                SELECT id, task_id, block_id, block_type, chapter_index, block_index,
                       section_path, text_content, text_hash, embedding_model,
                       embedding_dimension, created_at,
                       1 - (%s <=> %s) AS similarity
                FROM rag_document_blocks
                WHERE task_id = ?
                  AND embedding_model = '%s'
                  AND embedding_dimension = %d
                  AND embedding IS NOT NULL
                ORDER BY %s <=> %s
                LIMIT ?
                """.formatted(expression, queryCast, modelLiteral, dimension, expression, queryCast);

        return jdbcTemplate.query(sql, ps -> {
            ps.setString(1, queryVector);
            ps.setString(2, taskId);
            ps.setString(3, queryVector);
            ps.setInt(4, limit);
        }, this::mapScoredBlock);
    }

    private List<ScoredRagDocumentBlock> queryBinaryQuantized(String taskId,
                                                            String model,
                                                            String queryVector,
                                                            int dimension,
                                                            int limit,
                                                            int candidateMultiplier) {
        int candidateLimit = Math.max(limit, Math.min(10_000, limit * candidateMultiplier));
        String binaryExpression = "binary_quantize(embedding)::bit(" + dimension + ")";
        String binaryQuery = "binary_quantize(?::vector)::bit(" + dimension + ")";
        String modelLiteral = model.replace("'", "''");
        String sql = """
                WITH candidates AS MATERIALIZED (
                    SELECT id, task_id, block_id, block_type, chapter_index, block_index,
                           section_path, text_content, text_hash, embedding_model,
                           embedding_dimension, created_at, embedding
                    FROM rag_document_blocks
                    WHERE task_id = ?
                      AND embedding_model = '%s'
                      AND embedding_dimension = %d
                      AND embedding IS NOT NULL
                    ORDER BY %s <~> %s
                    LIMIT ?
                )
                SELECT id, task_id, block_id, block_type, chapter_index, block_index,
                       section_path, text_content, text_hash, embedding_model,
                       embedding_dimension, created_at,
                       1 - (embedding <=> ?::vector) AS similarity
                FROM candidates
                ORDER BY embedding <=> ?::vector
                LIMIT ?
                """.formatted(modelLiteral, dimension, binaryExpression, binaryQuery);

        return jdbcTemplate.query(sql, ps -> {
            ps.setString(1, taskId);
            ps.setString(2, queryVector);
            ps.setInt(3, candidateLimit);
            ps.setString(4, queryVector);
            ps.setString(5, queryVector);
            ps.setInt(6, limit);
        }, this::mapScoredBlock);
    }

    private ScoredRagDocumentBlock mapScoredBlock(ResultSet rs, int rowNum) throws SQLException {
        RagDocumentBlock block = new RagDocumentBlock();
        block.setId(rs.getLong("id"));
        block.setTaskId(rs.getString("task_id"));
        block.setBlockId(rs.getString("block_id"));
        block.setBlockType(rs.getString("block_type"));
        block.setChapterIndex(rs.getInt("chapter_index"));
        block.setBlockIndex(rs.getInt("block_index"));
        block.setSectionPath(rs.getString("section_path"));
        block.setTextContent(rs.getString("text_content"));
        block.setTextHash(rs.getString("text_hash"));
        block.setEmbeddingModel(rs.getString("embedding_model"));
        block.setEmbeddingDimension(rs.getInt("embedding_dimension"));
        Timestamp createdAt = rs.getTimestamp("created_at");
        block.setCreatedAt(createdAt != null ? createdAt.toLocalDateTime() : null);
        return new ScoredRagDocumentBlock(block, rs.getDouble("similarity"));
    }

    private static String toVectorLiteral(List<Double> vector) {
        List<String> values = new ArrayList<>(vector.size());
        for (Double value : vector) {
            if (value == null || !Double.isFinite(value)) {
                throw new IllegalArgumentException("Embedding vector contains a non-finite value");
            }
            values.add(Double.toString(value));
        }
        return "[" + String.join(",", values) + "]";
    }

    private static void validateDimension(Integer dimension) {
        if (dimension == null || dimension < 1 || dimension > MAX_VECTOR_DIMENSIONS) {
            throw new IllegalArgumentException(
                    "pgvector embedding dimension must be between 1 and " + MAX_VECTOR_DIMENSIONS);
        }
    }

    private static String requireModel(String embeddingModel) {
        if (embeddingModel == null || embeddingModel.isBlank()) {
            throw new IllegalArgumentException("Embedding model name is required");
        }
        return embeddingModel.trim();
    }

    private static String indexStrategy(int dimension) {
        if (dimension <= MAX_VECTOR_HNSW_DIMENSIONS) return "vector";
        if (dimension <= MAX_HALFVEC_HNSW_DIMENSIONS) return "halfvec";
        return "binary";
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (Exception e) {
            return Integer.toUnsignedString(value.hashCode(), 16);
        }
    }

    public record ScoredRagDocumentBlock(RagDocumentBlock block, double score) {
    }
}
