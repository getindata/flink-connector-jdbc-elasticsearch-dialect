package com.getindata.flink.connector.jdbc.elasticsearch.table;

import lombok.experimental.UtilityClass;
import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.connector.jdbc.datasource.connections.SimpleJdbcConnectionProvider;
import org.apache.flink.connector.jdbc.internal.options.InternalJdbcConnectionOptions;
import org.apache.flink.table.api.ValidationException;
import org.elasticsearch.xpack.sql.jdbc.EsType;

import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@UtilityClass
class EsColumnMetadata {

    public Map<String, String> readTextColumnsWithKeywordSubfield(
            InternalJdbcConnectionOptions options) {
        var connectionProvider = new SimpleJdbcConnectionProvider(options);
        try {
            var connection = connectionProvider.getOrEstablishConnection();
            return readTextColumnsWithKeywordSubfield(connection.getMetaData(), options.getTableName());
        } catch (SQLException | ClassNotFoundException | RuntimeException e) {
            throw new ValidationException(
                    "Could not read the columns of Elasticsearch table '%s'".formatted(options.getTableName()), e);
        } finally {
            connectionProvider.closeConnection();
        }
    }

    @VisibleForTesting
    Map<String, String> readTextColumnsWithKeywordSubfield(
            DatabaseMetaData metaData, String tableName) throws SQLException {
        var columnTypes = columnTypes(metaData, tableName);
        return columnTypes.keySet().stream()
                .filter(column -> hasType(columnTypes, column, EsType.TEXT))
                .map(column -> Map.entry(column, keywordSubfields(columnTypes, column)))
                // ES SQL rejects filters on a text column with several keyword subfields
                .filter(columnSubfields -> columnSubfields.getValue().size() == 1)
                .collect(Collectors.toMap(Map.Entry::getKey, columnSubfields -> columnSubfields.getValue().get(0)));
    }

    private Map<String, String> columnTypes(DatabaseMetaData metaData, String tableName)
            throws SQLException {
        Map<String, String> columnTypes = new LinkedHashMap<>();
        var tablePattern = escapeLikeWildcards(tableName, metaData.getSearchStringEscape());
        try (var columns = metaData.getColumns(null, null, tablePattern, null)) {
            while (columns.next()) {
                columnTypes.put(columns.getString("COLUMN_NAME"), columns.getString("TYPE_NAME"));
            }
        }
        return columnTypes;
    }

    private List<String> keywordSubfields(Map<String, String> columnTypes, String column) {
        var prefix = column + ".";
        return columnTypes.keySet().stream()
                .filter(candidate -> candidate.startsWith(prefix) && candidate.length() > prefix.length())
                .filter(candidate -> hasType(columnTypes, candidate, EsType.KEYWORD))
                .collect(Collectors.toList());
    }

    private boolean hasType(Map<String, String> columnTypes, String column, EsType type) {
        return type.getName().equalsIgnoreCase(columnTypes.get(column));
    }

    @VisibleForTesting
    String escapeLikeWildcards(String tableName, String escape) {
        if (escape == null || escape.isEmpty()) {
            return tableName;
        }
        var result = new StringBuilder(tableName.length());
        for (char c : tableName.toCharArray()) {
            if (c == '_' || c == '%' || escape.indexOf(c) >= 0) {
                result.append(escape);
            }
            result.append(c);
        }
        return result.toString();
    }
}
