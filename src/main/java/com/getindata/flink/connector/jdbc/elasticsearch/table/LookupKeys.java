package com.getindata.flink.connector.jdbc.elasticsearch.table;

import org.apache.flink.connector.jdbc.core.database.dialect.JdbcDialect;
import org.apache.flink.connector.jdbc.core.table.source.JdbcFilterPushdownPreparedStatementVisitor;
import org.apache.flink.streaming.api.lineage.LineageVertexProvider;
import org.apache.flink.table.expressions.FieldReferenceExpression;
import org.apache.flink.table.expressions.ResolvedExpression;
import org.apache.flink.table.functions.LookupFunction;
import org.apache.flink.table.types.DataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

record LookupKeys(List<LookupKey> keys) {

    static LookupKeys of(int[][] keys, DataType physicalRowDataType, FilterExtender extender) {
        var fieldNames = DataType.getFieldNames(physicalRowDataType);
        var fieldTypes = DataType.getFieldDataTypes(physicalRowDataType);
        var lookupKeys = new ArrayList<LookupKey>();
        for (int position = 0; position < keys.length; position++) {
            var fieldPosition = keys[position][0];
            var column = new FieldReferenceExpression(
                    fieldNames.get(fieldPosition), fieldTypes.get(fieldPosition), 0, fieldPosition);
            lookupKeys.add(new LookupKey(column, position, extender.ignoredSubfieldMarker(column)));
        }
        return new LookupKeys(List.copyOf(lookupKeys));
    }

    List<String> conditions(JdbcDialect dialect) {
        return keys.stream().map(key -> key.condition(dialect)).toList();
    }

    <T extends LookupFunction & LineageVertexProvider> LookupFunction wrap(T lookupFunction) {
        var recheckedKeys = keys.stream().map(LookupKey::recheckedKey).flatMap(Optional::stream).toList();
        if (recheckedKeys.isEmpty()) {
            return lookupFunction;
        }
        return new RecheckedKeysLookupFunction<>(lookupFunction, recheckedKeys);
    }

    private record LookupKey(
            FieldReferenceExpression column, int position, Optional<ResolvedExpression> ignoredSubfieldMarker) {

        // an extended key matches also documents with an ignored subfield value
        String condition(JdbcDialect dialect) {
            var condition = dialect.quoteIdentifier(column.getName()) + " = ?";
            return ignoredSubfieldMarker
                    .map(marker -> toSql(marker, dialect))
                    .map(markerSql -> "(%s OR %s)".formatted(condition, markerSql))
                    .orElse(condition);
        }

        Optional<RecheckedKeysLookupFunction.RecheckedKey> recheckedKey() {
            return ignoredSubfieldMarker.map(marker -> RecheckedKeysLookupFunction.RecheckedKey.of(
                    column.getOutputDataType().getLogicalType(), position, column.getFieldIndex()));
        }

        private static String toSql(ResolvedExpression expression, JdbcDialect dialect) {
            return expression
                    .accept(new JdbcFilterPushdownPreparedStatementVisitor(dialect::quoteIdentifier))
                    .orElseThrow(() -> new IllegalStateException("Could not convert to SQL: " + expression))
                    .getPredicate();
        }
    }
}
