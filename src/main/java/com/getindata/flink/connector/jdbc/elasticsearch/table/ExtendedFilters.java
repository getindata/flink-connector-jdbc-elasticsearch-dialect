package com.getindata.flink.connector.jdbc.elasticsearch.table;

import org.apache.flink.table.connector.source.abilities.SupportsFilterPushDown.Result;
import org.apache.flink.table.expressions.ResolvedExpression;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

record ExtendedFilters(List<ResolvedExpression> filters, Map<ResolvedExpression, ResolvedExpression> originals) {

    static ExtendedFilters of(List<ResolvedExpression> filters, FilterExtender extender) {
        Map<ResolvedExpression, ResolvedExpression> originals = new IdentityHashMap<>();
        var extendedFilters = new ArrayList<ResolvedExpression>();
        for (var filter : filters) {
            var extended = extender.extend(filter);
            originals.put(extended, filter);
            extendedFilters.add(extended);
        }
        return new ExtendedFilters(List.copyOf(extendedFilters), originals);
    }

    ResolvedExpression originalOf(ResolvedExpression extendedFilter) {
        return originals.get(extendedFilter);
    }

    // an extended filter is only a superset, so Flink has to check its original again
    Result toPlannerResult(Result result) {
        var accepted = result.getAcceptedFilters().stream().map(this::originalOf).toList();
        var recheckedAccepted = result.getAcceptedFilters().stream().filter(this::isRechecked);
        var remaining =
                Stream.concat(result.getRemainingFilters().stream(), recheckedAccepted)
                        .map(this::originalOf)
                        .toList();
        return Result.of(accepted, remaining);
    }

    private boolean isRechecked(ResolvedExpression extendedFilter) {
        return originalOf(extendedFilter) != extendedFilter;
    }
}
