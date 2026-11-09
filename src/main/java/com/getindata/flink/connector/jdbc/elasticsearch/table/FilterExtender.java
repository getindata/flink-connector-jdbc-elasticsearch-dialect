package com.getindata.flink.connector.jdbc.elasticsearch.table;

import lombok.RequiredArgsConstructor;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.expressions.CallExpression;
import org.apache.flink.table.expressions.FieldReferenceExpression;
import org.apache.flink.table.expressions.ResolvedExpression;
import org.apache.flink.table.functions.BuiltInFunctionDefinition;
import org.apache.flink.table.functions.BuiltInFunctionDefinitions;
import org.apache.flink.table.functions.FunctionDefinition;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

// Values over ignore_above have no keyword subfield, so conditions on such columns also match
// documents without it. Flink checks the resulting superset again.
@RequiredArgsConstructor
class FilterExtender {

    public static final FilterExtender NONE = new FilterExtender(Map::of);

    private static final Set<FunctionDefinition> JUNCTIONS =
            Set.of(BuiltInFunctionDefinitions.AND, BuiltInFunctionDefinitions.OR);

    // run as exists on the text field, which ignore_above does not affect
    private static final Set<FunctionDefinition> NULL_CHECKS =
            Set.of(BuiltInFunctionDefinitions.IS_NULL, BuiltInFunctionDefinitions.IS_NOT_NULL);

    // read only when a string column is referenced
    private final Supplier<Map<String, String>> recheckColumns;

    // returns the same instance when nothing is extended
    ResolvedExpression extend(ResolvedExpression filter) {
        if (!(filter instanceof CallExpression call) || NULL_CHECKS.contains(call.getFunctionDefinition())) {
            return filter;
        }
        if (JUNCTIONS.contains(call.getFunctionDefinition())) {
            return extendJunction(call);
        }
        return extendCondition(call);
    }

    private ResolvedExpression extendJunction(CallExpression junction) {
        var children = junction.getResolvedChildren();
        var extended = children.stream().map(this::extend).toList();
        if (sameInstances(children, extended)) {
            return junction;
        }
        return junction.replaceArgs(extended, junction.getOutputDataType());
    }

    private ResolvedExpression extendCondition(CallExpression condition) {
        var markers =
                fieldReferences(condition)
                        .map(this::ignoredSubfieldMarker)
                        .flatMap(Optional::stream)
                        .distinct()
                        .toList();
        if (markers.isEmpty()) {
            return condition;
        }
        return or(condition, anyOf(markers));
    }

    Optional<ResolvedExpression> ignoredSubfieldMarker(FieldReferenceExpression column) {
        if (!EsTypes.isStringType(column.getOutputDataType())) {
            return Optional.empty();
        }
        return Optional.ofNullable(recheckColumns.get().get(column.getName()))
                .map(subfield -> ignoredSubfieldMarker(column, subfield));
    }

    private static Stream<FieldReferenceExpression> fieldReferences(ResolvedExpression expression) {
        if (expression instanceof FieldReferenceExpression field) {
            return Stream.of(field);
        }
        return expression.getResolvedChildren().stream().flatMap(FilterExtender::fieldReferences);
    }

    // ("col.keyword" IS NULL AND "col" IS NOT NULL); the indexes are not used by the push-down
    private static ResolvedExpression ignoredSubfieldMarker(FieldReferenceExpression column, String subfield) {
        var subfieldReference = new FieldReferenceExpression(subfield, DataTypes.STRING(), 0, 0);
        return call(
                BuiltInFunctionDefinitions.AND,
                call(BuiltInFunctionDefinitions.IS_NULL, subfieldReference),
                call(BuiltInFunctionDefinitions.IS_NOT_NULL, column));
    }

    // the push-down renders only binary AND/OR
    private static ResolvedExpression anyOf(List<ResolvedExpression> expressions) {
        return expressions.stream().reduce(FilterExtender::or).orElseThrow();
    }

    private static ResolvedExpression or(ResolvedExpression left, ResolvedExpression right) {
        return call(BuiltInFunctionDefinitions.OR, left, right);
    }

    private static CallExpression call(BuiltInFunctionDefinition function, ResolvedExpression... args) {
        return CallExpression.permanent(function, List.of(args), DataTypes.BOOLEAN());
    }

    private static boolean sameInstances(List<ResolvedExpression> original, List<ResolvedExpression> extended) {
        for (int i = 0; i < original.size(); i++) {
            if (original.get(i) != extended.get(i)) {
                return false;
            }
        }
        return true;
    }
}
