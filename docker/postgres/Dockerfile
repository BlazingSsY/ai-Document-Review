FROM postgres:16-alpine
COPY backend/src/main/resources/schema.sql /docker-entrypoint-initdb.d/01-schema.sql
RUN sed -i 's/\r$//' /docker-entrypoint-initdb.d/01-schema.sql
