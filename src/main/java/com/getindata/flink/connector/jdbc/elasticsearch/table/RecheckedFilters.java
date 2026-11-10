package com.getindata.flink.connector.jdbc.elasticsearch.table;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.connector.source.abilities.SupportsFilterPushDown.Result;
import org.apache.flink.table.expressions.CallExpression;
import org.apache.flink.table.expressions.FieldReferenceExpression;
import org.apache.flink.table.expressions.ResolvedExpression;
import org.apache.flink.table.functions.BuiltInFunctionDefinitions;
import org.apache.flink.table.functions.FunctionDefinition;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
final class RecheckedFilters {

    // IS NULL and IS NOT NULL run as exists on the text field, which ignore_above does not affect
    private static final Set<FunctionDefinition> NULL_CHECKS =
            Set.of(BuiltInFunctionDefinitions.IS_NULL, BuiltInFunctionDefinitions.IS_NOT_NULL);

    private final List<ResolvedExpression> filters;
    private final Map<ResolvedExpression, ResolvedExpression> originals;

    static RecheckedFilters of(List<ResolvedExpression> filters, KeywordSubfields keywordSubfields) {
        Map<ResolvedExpression, ResolvedExpression> originals = new IdentityHashMap<>();
        var recheckedFilters = new ArrayList<ResolvedExpression>();
        for (var filter : filters) {
            var rechecked = includingIgnoredKeywords(filter, keywordSubfields);
            originals.put(rechecked, filter);
            recheckedFilters.add(rechecked);
        }
        return new RecheckedFilters(List.copyOf(recheckedFilters), originals);
    }

    // returns the same instance when no column has a keyword subfield; filters which are not calls are not pushed down
    static ResolvedExpression includingIgnoredKeywords(ResolvedExpression filter, KeywordSubfields keywordSubfields) {
        if (!(filter instanceof CallExpression)) {
            return filter;
        }
        var conditions =
                fieldReferences(filter)
                        .map(column -> ignoredKeywordCondition(column, keywordSubfields))
                        .flatMap(Optional::stream)
                        .distinct()
                        .toList();
        if (conditions.isEmpty()) {
            return filter;
        }
        return or(filter, anyOf(conditions));
    }

    List<ResolvedExpression> filters() {
        return filters;
    }

    ResolvedExpression originalOf(ResolvedExpression recheckedFilter) {
        return originals.get(recheckedFilter);
    }

    // a filter including ignored keywords matches a superset, so Flink has to recheck its original
    Result toResult(Result result) {
        var accepted = result.getAcceptedFilters().stream().map(this::originalOf).toList();
        var recheckedAccepted = result.getAcceptedFilters().stream().filter(this::isRechecked);
        var remaining =
                Stream.concat(result.getRemainingFilters().stream(), recheckedAccepted)
                        .map(this::originalOf)
                        .toList();
        return Result.of(accepted, remaining);
    }

    private boolean isRechecked(ResolvedExpression recheckedFilter) {
        return originalOf(recheckedFilter) != recheckedFilter;
    }

    private static Optional<ResolvedExpression> ignoredKeywordCondition(
            FieldReferenceExpression column, KeywordSubfields keywordSubfields) {
        return keywordSubfields
                .keywordSubfieldOf(column.getName(), column.getOutputDataType())
                .map(subfield -> IgnoredKeywordCondition.expression(column, subfield));
    }

    private static Stream<FieldReferenceExpression> fieldReferences(ResolvedExpression expression) {
        if (expression instanceof FieldReferenceExpression field) {
            return Stream.of(field);
        }
        if (expression instanceof CallExpression call && NULL_CHECKS.contains(call.getFunctionDefinition())) {
            return Stream.empty();
        }
        return expression.getResolvedChildren().stream().flatMap(RecheckedFilters::fieldReferences);
    }

    // the push-down renders only binary AND/OR
    private static ResolvedExpression anyOf(List<ResolvedExpression> expressions) {
        return expressions.stream().reduce(RecheckedFilters::or).orElseThrow();
    }

    private static ResolvedExpression or(ResolvedExpression left, ResolvedExpression right) {
        return CallExpression.permanent(BuiltInFunctionDefinitions.OR, List.of(left, right), DataTypes.BOOLEAN());
    }
}
