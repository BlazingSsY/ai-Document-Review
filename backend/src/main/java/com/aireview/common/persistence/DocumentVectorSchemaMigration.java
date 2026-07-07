package com.aireview.common.persistence;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class DocumentVectorSchemaMigration implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        migrateLegacyTextVectors();
        ensureDimensionConstraint();
    }

    private void migrateLegacyTextVectors() {
        Boolean legacyColumnExists = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM information_schema.columns
                    WHERE table_schema = current_schema()
                      AND table_name = 'document_blocks'
                      AND column_name = 'embedding_vector'
                )
                """, Boolean.class);
        if (!Boolean.TRUE.equals(legacyColumnExists)) {
            return;
        }

        int migrated = jdbcTemplate.update("""
                UPDATE document_blocks
                SET embedding = NULLIF(BTRIM(embedding_vector), '')::vector
                WHERE embedding IS NULL
                  AND NULLIF(BTRIM(embedding_vector), '') IS NOT NULL
                """);
        if (migrated > 0) {
            log.info("Migrated {} legacy document vector(s) to pgvector", migrated);
        }
    }

    private void ensureDimensionConstraint() {
        Boolean constraintExists = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM pg_constraint
                    WHERE conname = 'chk_document_blocks_embedding_dimension'
                      AND conrelid = 'document_blocks'::regclass
                )
                """, Boolean.class);
        if (Boolean.TRUE.equals(constraintExists)) {
            return;
        }

        jdbcTemplate.execute("""
                ALTER TABLE document_blocks
                ADD CONSTRAINT chk_document_blocks_embedding_dimension
                CHECK (
                    embedding IS NULL
                    OR (
                        embedding_dimension BETWEEN 1 AND 16000
                        AND vector_dims(embedding) = embedding_dimension
                    )
                )
                """);
        log.info("Created document block pgvector dimension constraint");
    }
}
