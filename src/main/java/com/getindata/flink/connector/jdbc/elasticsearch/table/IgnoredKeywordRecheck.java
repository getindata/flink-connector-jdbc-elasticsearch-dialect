package com.getindata.flink.connector.jdbc.elasticsearch.table;

import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.connector.jdbc.core.table.FilterHandlingPolicy;
import org.apache.flink.connector.jdbc.internal.options.InternalJdbcConnectionOptions;
import org.apache.flink.table.expressions.ResolvedExpression;
import org.apache.flink.table.types.DataType;

import javax.annotation.Nullable;

import java.util.List;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
@EqualsAndHashCode
class IgnoredKeywordRecheck {

    private final boolean enabled;
    private final FilterHandlingPolicy filterHandlingPolicy;
    private final InternalJdbcConnectionOptions options;
    @EqualsAndHashCode.Exclude
    @Nullable
    private Map<String, String> recheckColumns;

    @VisibleForTesting
    boolean isEnabled() {
        return enabled;
    }

    ExtendedFilters extendFilters(List<ResolvedExpression> filters) {
        return ExtendedFilters.of(filters, filterExtender());
    }

    LookupKeys lookupKeys(int[][] keys, DataType physicalRowDataType) {
        return LookupKeys.of(keys, physicalRowDataType, lookupKeyExtender());
    }

    // filters which are not pushed down need not be extended
    private FilterExtender filterExtender() {
        if (enabled && filterHandlingPolicy != FilterHandlingPolicy.NEVER) {
            return new FilterExtender(this::recheckColumns);
        }
        return FilterExtender.NONE;
    }

    // lookup keys are always part of the lookup query
    private FilterExtender lookupKeyExtender() {
        if (enabled) {
            return new FilterExtender(this::recheckColumns);
        }
        return FilterExtender.NONE;
    }

    private Map<String, String> recheckColumns() {
        if (recheckColumns == null) {
            recheckColumns = EsColumnMetadata.textColumnsWithSingleKeywordSubfield(options);
            LOG.debug("Filters on text columns with a single keyword subfield of {} are checked again by Flink: {}",
                    options.getTableName(), recheckColumns);
        }
        return recheckColumns;
    }

    @VisibleForTesting
    void setRecheckColumns(Map<String, String> columns) {
        this.recheckColumns = Map.copyOf(columns);
    }
}
