/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.getindata.flink.connector.jdbc.elasticsearch.table;

import lombok.EqualsAndHashCode;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.connector.jdbc.core.table.FilterHandlingPolicy;
import org.apache.flink.connector.jdbc.core.table.source.JdbcDynamicTableSource;
import org.apache.flink.connector.jdbc.core.table.source.JdbcFilterPushdownPreparedStatementVisitor;
import org.apache.flink.connector.jdbc.core.table.source.ParameterizedPredicate;
import org.apache.flink.connector.jdbc.internal.options.InternalJdbcConnectionOptions;
import org.apache.flink.connector.jdbc.internal.options.JdbcReadOptions;
import org.apache.flink.table.connector.Projection;
import org.apache.flink.table.connector.source.DynamicTableSource;
import org.apache.flink.table.connector.source.lookup.LookupFunctionProvider;
import org.apache.flink.table.connector.source.lookup.PartialCachingLookupProvider;
import org.apache.flink.table.connector.source.lookup.cache.LookupCache;
import org.apache.flink.table.expressions.CallExpression;
import org.apache.flink.table.expressions.ResolvedExpression;
import org.apache.flink.table.types.DataType;
import org.apache.flink.table.types.logical.RowType;

import javax.annotation.Nullable;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@EqualsAndHashCode(callSuper = false)
public class ElasticsearchDynamicTableSource extends JdbcDynamicTableSource {

    // parent fields are private and have to be replicated
    private final InternalJdbcConnectionOptions options;
    private final JdbcReadOptions readOptions;
    private final int lookupMaxRetryTimes;
    @Nullable
    private final LookupCache cache;
    private final FilterHandlingPolicy filterHandlingPolicy;
    private DataType physicalRowDataType;
    private long limit = -1;
    @EqualsAndHashCode.Exclude
    private final List<ResolvedExpression> acceptedFilters = new ArrayList<>();
    private final List<String> resolvedPredicates = new ArrayList<>();
    private Serializable[] pushdownParams = new Serializable[0];
    // not part of equals/hashCode, as in the parent
    @EqualsAndHashCode.Exclude
    private final String tableIdentifier;
    private final KeywordSubfields keywordSubfields;

    ElasticsearchDynamicTableSource(
            InternalJdbcConnectionOptions options,
            JdbcReadOptions readOptions,
            int lookupMaxRetryTimes,
            @Nullable LookupCache cache,
            FilterHandlingPolicy filterHandlingPolicy,
            DataType physicalRowDataType,
            String tableIdentifier,
            KeywordSubfields keywordSubfields) {
        super(options,
            readOptions,
            lookupMaxRetryTimes,
            cache,
            filterHandlingPolicy,
            physicalRowDataType,
            tableIdentifier);
        this.options = options;
        this.readOptions = readOptions;
        this.lookupMaxRetryTimes = lookupMaxRetryTimes;
        this.cache = cache;
        this.filterHandlingPolicy = filterHandlingPolicy;
        this.physicalRowDataType = physicalRowDataType;
        this.tableIdentifier = tableIdentifier;
        this.keywordSubfields = keywordSubfields;
    }

    @VisibleForTesting
    KeywordSubfields getKeywordSubfields() {
        return keywordSubfields;
    }

    @Override
    public LookupRuntimeProvider getLookupRuntimeProvider(LookupContext context) {
        final RowType rowType = (RowType) physicalRowDataType.getLogicalType();
        var lookupKeys = LookupKeys.of(context.getKeys(), physicalRowDataType, keywordSubfields);
        var esLookupFunction =
                new ElasticsearchRowDataLookupFunction(
                        options,
                        lookupMaxRetryTimes,
                        DataType.getFieldNames(physicalRowDataType),
                        DataType.getFieldDataTypes(physicalRowDataType),
                        lookupKeys.names(),
                        lookupKeys.conditions(options.getDialect()),
                        rowType,
                        List.copyOf(resolvedPredicates),
                        pushdownParams.clone());
        var lookupFunction = lookupKeys.withExactKeyMatch(esLookupFunction);
        if (cache != null) {
            return PartialCachingLookupProvider.of(lookupFunction, cache);
        } else {
            return LookupFunctionProvider.of(lookupFunction);
        }
    }

    @Override
    public void applyProjection(int[][] projectedFields, DataType producedDataType) {
        super.applyProjection(projectedFields, producedDataType);
        this.physicalRowDataType = Projection.of(projectedFields).project(physicalRowDataType);
    }

    @Override
    public void applyLimit(long limit) {
        super.applyLimit(limit);
        this.limit = limit;
    }

    @Override
    public Result applyFilters(List<ResolvedExpression> filters) {
        // nothing is pushed down, so the keyword subfields need not be read
        if (filterHandlingPolicy == FilterHandlingPolicy.NEVER) {
            return super.applyFilters(filters);
        }
        var recheckedFilters = RecheckedFilters.of(filters, keywordSubfields);
        Result result = super.applyFilters(recheckedFilters.filters());
        // Mirror the predicates accepted by the parent, in the same order, so that the lookup
        // function receives the same SQL fragments and parameters as the scan.
        for (ResolvedExpression filter : result.getAcceptedFilters()) {
            ParameterizedPredicate predicate =
                    parseFilterToPredicate(filter).orElseThrow(
                            () -> new IllegalStateException(
                                    "Filter accepted for push down could not be converted to a predicate: " + filter));
            acceptedFilters.add(recheckedFilters.originalOf(filter));
            resolvedPredicates.add(predicate.getPredicate());
            pushdownParams = ArrayUtils.addAll(pushdownParams, predicate.getParameters());
        }
        return recheckedFilters.toResult(result);
    }

    // copy JdbcDynamicTableSource#parseFilterToPredicate, private in the parent
    private Optional<ParameterizedPredicate> parseFilterToPredicate(ResolvedExpression filter) {
        if (filter instanceof CallExpression callExp) {
            return callExp.accept(
                    new JdbcFilterPushdownPreparedStatementVisitor(
                            this.options.getDialect()::quoteIdentifier));
        }
        return Optional.empty();
    }

    @Override
    public DynamicTableSource copy() {
        ElasticsearchDynamicTableSource newSource =
                new ElasticsearchDynamicTableSource(
                        options,
                        readOptions,
                        lookupMaxRetryTimes,
                        cache,
                        filterHandlingPolicy,
                        physicalRowDataType,
                        tableIdentifier,
                        keywordSubfields);
        // The parent's push down state is private, so it is rebuilt by re-applying the same
        // abilities. Filters are referenced by name, so re-applying them after projection is safe.
        if (!acceptedFilters.isEmpty()) {
            newSource.applyFilters(List.copyOf(acceptedFilters));
        }
        if (limit >= 0) {
            newSource.applyLimit(limit);
        }
        return newSource;
    }
}
