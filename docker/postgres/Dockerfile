FROM pgvector/pgvector:0.8.2-pg16
COPY backend/src/main/resources/schema.sql /docker-entrypoint-initdb.d/01-schema.sql
RUN sed -i 's/\r$//' /docker-entrypoint-initdb.d/01-schema.sql
