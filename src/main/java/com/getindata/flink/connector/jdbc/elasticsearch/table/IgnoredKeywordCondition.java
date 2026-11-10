package com.getindata.flink.connector.jdbc.elasticsearch.table;

import lombok.experimental.UtilityClass;
import org.apache.flink.connector.jdbc.core.database.dialect.JdbcDialect;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.expressions.CallExpression;
import org.apache.flink.table.expressions.FieldReferenceExpression;
import org.apache.flink.table.expressions.ResolvedExpression;
import org.apache.flink.table.functions.BuiltInFunctionDefinition;
import org.apache.flink.table.functions.BuiltInFunctionDefinitions;

import java.util.List;

// Values over ignore_above have no keyword subfield, so conditions on such columns also have to match
// documents without it: ("col.keyword" IS NULL AND "col" IS NOT NULL). Flink rechecks the resulting superset.
@UtilityClass
class IgnoredKeywordCondition {

    // the indexes of the subfield reference are not used by the push-down
    ResolvedExpression expression(FieldReferenceExpression column, String subfield) {
        var subfieldReference = new FieldReferenceExpression(subfield, DataTypes.STRING(), 0, 0);
        return call(
                BuiltInFunctionDefinitions.AND,
                call(BuiltInFunctionDefinitions.IS_NULL, subfieldReference),
                call(BuiltInFunctionDefinitions.IS_NOT_NULL, column));
    }

    String sql(JdbcDialect dialect, String column, String subfield) {
        return "((%s IS NULL) AND (%s IS NOT NULL))"
                .formatted(dialect.quoteIdentifier(subfield), dialect.quoteIdentifier(column));
    }

    private CallExpression call(BuiltInFunctionDefinition function, ResolvedExpression... args) {
        return CallExpression.permanent(function, List.of(args), DataTypes.BOOLEAN());
    }
}
