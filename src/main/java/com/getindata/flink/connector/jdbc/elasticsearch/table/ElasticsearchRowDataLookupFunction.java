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

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.connector.jdbc.core.database.dialect.JdbcDialect;
import org.apache.flink.connector.jdbc.core.database.dialect.JdbcDialectConverter;
import org.apache.flink.connector.jdbc.datasource.connections.JdbcConnectionProvider;
import org.apache.flink.connector.jdbc.datasource.connections.SimpleJdbcConnectionProvider;
import org.apache.flink.connector.jdbc.internal.options.InternalJdbcConnectionOptions;
import org.apache.flink.connector.jdbc.statement.FieldNamedPreparedStatement;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.functions.FunctionContext;
import org.apache.flink.table.functions.LookupFunction;
import org.apache.flink.table.types.DataType;
import org.apache.flink.table.types.logical.LogicalType;
import org.apache.flink.table.types.logical.RowType;

import java.io.Serializable;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.apache.flink.util.Preconditions.checkArgument;

/**
 * Modified copy of JdbcRowDataLookupFunction. The lookup query uses positional parameters
 * ({@code ?}), like the scan, instead of named parameters parsed by FieldNamedPreparedStatement,
 * which breaks on column and table names containing e.g. '.', '@', ':' or '?'.
 * Each method is marked as copied, modified or new.
 */
@Slf4j
public class ElasticsearchRowDataLookupFunction extends LookupFunction {

    private static final long serialVersionUID = 1L;

    @Getter(value = AccessLevel.PACKAGE, onMethod_ = @VisibleForTesting)
    private final String query;
    private final JdbcConnectionProvider connectionProvider;
    // replaces keyNames of JdbcRowDataLookupFunction, only their number is needed
    private final int numberOfKeys;
    private final int maxRetryTimes;
    private final JdbcDialectConverter jdbcDialectConverter;
    private final JdbcDialectConverter lookupKeyRowConverter;

    private final List<String> resolvedPredicates;
    private final Serializable[] pushdownParams;

    private transient FieldNamedPreparedStatement statement;

    public ElasticsearchRowDataLookupFunction(
            @NonNull InternalJdbcConnectionOptions options,
            int maxRetryTimes,
            @NonNull String[] fieldNames,
            @NonNull DataType[] fieldTypes,
            @NonNull String[] keyNames,
            RowType rowType,
            @NonNull List<String> resolvedPredicates,
            @NonNull Serializable[] pushdownParams) {
        this.connectionProvider = new SimpleJdbcConnectionProvider(options);
        List<String> nameList = Arrays.asList(fieldNames);
        DataType[] keyTypes = Arrays.stream(keyNames)
                .map(s -> {
                    checkArgument(nameList.contains(s), "keyName %s can't find in fieldNames %s.", s, nameList);
                    return fieldTypes[nameList.indexOf(s)];
                }).toArray(DataType[]::new);
        this.maxRetryTimes = maxRetryTimes;
        JdbcDialect jdbcDialect = options.getDialect();
        this.numberOfKeys = keyNames.length;
        this.query = buildLookupQuery(jdbcDialect, options.getTableName(), fieldNames, keyNames);
        this.jdbcDialectConverter = jdbcDialect.getRowConverter(rowType);
        this.lookupKeyRowConverter = jdbcDialect.getRowConverter(RowType.of(
                Arrays.stream(keyTypes).map(DataType::getLogicalType).toArray(LogicalType[]::new)));
        this.resolvedPredicates = resolvedPredicates;
        this.pushdownParams = pushdownParams;
    }

    @VisibleForTesting
    static String buildLookupQuery(
            JdbcDialect dialect,
            String tableName,
            String[] selectFields,
            String[] keyNames) {
        String selectExpressions =
                Arrays.stream(selectFields)
                        .map(dialect::quoteIdentifier)
                        .collect(Collectors.joining(", "));
        String conditions = Arrays.stream(keyNames)
                .map(keyName -> dialect.quoteIdentifier(keyName) + " = ?")
                .collect(Collectors.joining(" AND "));
        return "SELECT "
                + selectExpressions
                + " FROM "
                + dialect.quoteIdentifier(tableName)
                + (keyNames.length > 0 ? " WHERE " + conditions : "");
    }

    // copy JdbcRowDataLookupFunction#open
    @Override
    public void open(FunctionContext context) throws Exception {
        try {
            establishConnectionAndStatement();
        } catch (SQLException sqe) {
            throw new IllegalArgumentException("open() failed.", sqe);
        } catch (ClassNotFoundException cnfe) {
            throw new IllegalArgumentException("JDBC driver class not found.", cnfe);
        }
    }

    // copy JdbcRowDataLookupFunction#lookup, parameters are bound by bindParameters
    @Override
    public Collection<RowData> lookup(RowData keyRow) {
        for (int retry = 0; retry <= maxRetryTimes; retry++) {
            try {
                statement = bindParameters(statement, keyRow);
                try (ResultSet resultSet = statement.executeQuery()) {
                    ArrayList<RowData> rows = new ArrayList<>();
                    while (resultSet.next()) {
                        RowData row = jdbcDialectConverter.toInternal(resultSet);
                        rows.add(row);
                    }
                    rows.trimToSize();
                    return rows;
                }
            } catch (SQLException e) {
                LOG.error("JDBC lookup query failed, retry times = {}", retry, e);
                if (retry >= maxRetryTimes) {
                    throw new RuntimeException("Execution of JDBC statement failed.", e);
                }

                try {
                    if (!connectionProvider.isConnectionValid()) {
                        statement.close();
                        connectionProvider.closeConnection();
                        establishConnectionAndStatement();
                    }
                } catch (SQLException | ClassNotFoundException exception) {
                    LOG.error(
                            "JDBC connection is not valid, and reestablish connection failed",
                            exception);
                    throw new RuntimeException("Reestablish JDBC connection failed", exception);
                }

                try {
                    Thread.sleep(1000L * retry);
                } catch (InterruptedException e1) {
                    throw new RuntimeException(e1);
                }
            }
        }
        return Collections.emptyList();
    }

    @VisibleForTesting
    FieldNamedPreparedStatement bindParameters(FieldNamedPreparedStatement statement, RowData keyRow)
            throws SQLException {
        statement.clearParameters();
        statement = lookupKeyRowConverter.toExternal(keyRow, statement);
        return setPredicateParams(statement);
    }

    private FieldNamedPreparedStatement setPredicateParams(FieldNamedPreparedStatement statement) throws SQLException {
        for (int i = 0; i < pushdownParams.length; ++i) {
            statement.setObject(i + numberOfKeys, pushdownParams[i]);
        }
        return statement;
    }

    private void establishConnectionAndStatement() throws SQLException, ClassNotFoundException {
        statement = prepareStatement(connectionProvider.getOrEstablishConnection());
    }

    // Modified copy JdbcRowDataLookupFunction#establishConnectionAndStatement: the statement is
    // prepared directly with positional parameters, without FieldNamedPreparedStatement parsing.
    @VisibleForTesting
    FieldNamedPreparedStatement prepareStatement(Connection dbConn) throws SQLException {
        String additionalPredicates = "";
        if (!resolvedPredicates.isEmpty()) {
            String joinedConditions =
                    resolvedPredicates.stream()
                            .map(pred -> String.format("(%s)", pred))
                            .collect(Collectors.joining(" AND "));
            if (numberOfKeys == 0) {
                additionalPredicates = " WHERE " + joinedConditions;
            } else {
                additionalPredicates = " AND " + joinedConditions;
            }
        }
        return new PositionalPreparedStatement(dbConn.prepareStatement(query + additionalPredicates));
    }

    // copy JdbcRowDataLookupFunction#close
    @Override
    public void close() {
        if (statement != null) {
            try {
                statement.close();
            } catch (SQLException e) {
                LOG.info("JDBC statement could not be closed: " + e.getMessage());
            } finally {
                statement = null;
            }
        }

        connectionProvider.closeConnection();
    }
}
