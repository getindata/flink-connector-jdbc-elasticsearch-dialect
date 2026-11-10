package com.getindata.flink.connector.jdbc.elasticsearch.table;

import org.apache.flink.connector.jdbc.core.database.dialect.JdbcDialect;
import org.apache.flink.streaming.api.lineage.LineageVertexProvider;
import org.apache.flink.table.functions.LookupFunction;
import org.apache.flink.table.types.DataType;
import org.apache.flink.table.types.logical.LogicalType;
import org.apache.flink.util.Preconditions;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

record LookupKeys(List<LookupKey> keys) {

    static LookupKeys of(int[][] keys, DataType physicalRowDataType, KeywordSubfields keywordSubfields) {
        var fieldNames = DataType.getFieldNames(physicalRowDataType);
        var fieldTypes = DataType.getFieldDataTypes(physicalRowDataType);
        var lookupKeys = new ArrayList<LookupKey>();
        for (int position = 0; position < keys.length; position++) {
            Preconditions.checkArgument(keys[position].length == 1, "JDBC only support non-nested look up keys");
            var fieldIndex = keys[position][0];
            var name = fieldNames.get(fieldIndex);
            var type = fieldTypes.get(fieldIndex);
            lookupKeys.add(new LookupKey(
                    name, type.getLogicalType(), position, fieldIndex, keywordSubfields.keywordSubfieldOf(name, type)));
        }
        return new LookupKeys(List.copyOf(lookupKeys));
    }

    List<String> names() {
        return keys.stream().map(LookupKey::name).toList();
    }

    List<String> conditions(JdbcDialect dialect) {
        return keys.stream().map(key -> key.condition(dialect)).toList();
    }

    <T extends LookupFunction & LineageVertexProvider> LookupFunction withExactKeyMatch(T lookupFunction) {
        var keyMatches = keys.stream().map(LookupKey::keyMatch).flatMap(Optional::stream).toList();
        if (keyMatches.isEmpty()) {
            return lookupFunction;
        }
        return new ExactKeyMatchLookupFunction<>(lookupFunction, keyMatches);
    }

    private record LookupKey(
            String name, LogicalType type, int position, int fieldIndex, Optional<String> keywordSubfield) {

        String condition(JdbcDialect dialect) {
            var equalsKey = dialect.quoteIdentifier(name) + " = ?";
            return keywordSubfield
                    .map(subfield -> "(%s OR %s)"
                            .formatted(equalsKey, IgnoredKeywordCondition.sql(dialect, name, subfield)))
                    .orElse(equalsKey);
        }

        Optional<ExactKeyMatchLookupFunction.KeyMatch> keyMatch() {
            return keywordSubfield.map(ignored -> ExactKeyMatchLookupFunction.KeyMatch.of(type, position, fieldIndex));
        }
    }
}
