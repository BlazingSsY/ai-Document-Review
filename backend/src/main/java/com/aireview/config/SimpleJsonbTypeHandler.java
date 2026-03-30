package com.aireview.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.*;
import java.util.Map;

/**
 * Simple JSONB TypeHandler that doesn't depend on PostgreSQL-specific classes.
 * Uses standard JDBC string handling for JSONB fields.
 */
@MappedTypes(Map.class)
public class SimpleJsonbTypeHandler extends BaseTypeHandler<Map<String, Object>> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, Map<String, Object> parameter, JdbcType jdbcType) throws SQLException {
        try {
            String jsonValue = MAPPER.writeValueAsString(parameter);
            ps.setObject(i, jsonValue, Types.OTHER);
        } catch (JsonProcessingException e) {
            throw new SQLException("Failed to serialize Map to JSON", e);
        }
    }

    @Override
    public Map<String, Object> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public Map<String, Object> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public Map<String, Object> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String json) throws SQLException {
        if (json == null) return null;
        try {
            return MAPPER.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            throw new SQLException("Failed to parse JSON to Map", e);
        }
    }
}