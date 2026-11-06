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


import com.getindata.flink.connector.jdbc.elasticsearch.ElasticsearchTestBase;
import com.getindata.flink.connector.jdbc.elasticsearch.database.dialect.ElasticsearchDialect;
import com.getindata.flink.connector.jdbc.elasticsearch.testutils.ElasticsearchMetadata;
import com.getindata.flink.connector.jdbc.elasticsearch.testutils.ElasticsearchRestClient;
import org.apache.flink.connector.jdbc.testutils.tables.TableRow;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.apache.flink.table.connector.source.lookup.cache.LookupCache;
import org.apache.flink.table.data.GenericRowData;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.data.TimestampData;
import org.apache.flink.table.planner.factories.TestValuesTableFactory;
import org.apache.flink.table.runtime.functions.table.lookup.LookupCacheManager;
import org.apache.flink.table.test.lookup.cache.LookupCacheAssert;
import org.apache.flink.test.util.AbstractTestBase;
import org.apache.flink.types.Row;
import org.apache.flink.util.CollectionUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.getindata.flink.connector.jdbc.elasticsearch.testutils.ElasticsearchBulkBuilder.createBulkContent;
import static com.getindata.flink.connector.jdbc.elasticsearch.testutils.ElasticsearchIndexSchemaBuilder.buildIndexSchema;
import static org.apache.flink.connector.jdbc.testutils.tables.TableBuilder.field;
import static org.apache.flink.connector.jdbc.testutils.tables.TableBuilder.tableRow;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Table Source ITCase for {@link ElasticsearchDialect}.
 */
public class ElasticsearchDynamicTableSourceITCase extends AbstractTestBase
        implements ElasticsearchTestBase {

    private ElasticsearchRestClient client;
    private TableEnvironment tEnv;
    private final TableRow inputTable = createInputTable();

    @BeforeEach
    void beforeEach() throws Exception {
        client = new ElasticsearchRestClient((ElasticsearchMetadata) getMetadata());

        client.createIndex(inputTable.getTableName(), buildIndexSchema(inputTable));
        client.addDataBulk(inputTable.getTableName(), createBulkContent(inputTable, getTestData()));

        tEnv = StreamTableEnvironment.create(StreamExecutionEnvironment.getExecutionEnvironment());
    }

    @AfterEach
    void afterEach() throws Exception {
        if (client != null) {
            client.deleteIndex(inputTable.getTableName());
        }
    }

    @Test
    void testJdbcSource() {
        String testTable = "testTable";
        tEnv.executeSql(inputTable.getCreateQueryForFlink(getMetadata(), testTable));

        List<Row> collected = executeQuery("SELECT * FROM " + testTable);

        assertThat(collected).containsExactlyInAnyOrderElementsOf(getTestData());
    }

    @Test
    void testProject() {
        String testTable = "testTable";
        tEnv.executeSql(
                inputTable.getCreateQueryForFlink(
                        getMetadata(),
                        testTable,
                        Arrays.asList(
                                "'scan.partition.column'='id'",
                                "'scan.partition.num'='2'",
                                "'scan.partition.lower-bound'='0'",
                                "'scan.partition.upper-bound'='100'")));

        String fields = String.join(",", Arrays.copyOfRange(inputTable.getTableFields(), 0, 3));
        List<Row> collected = executeQuery(String.format("SELECT %s FROM %s", fields, testTable));

        List<Row> expected =
                getTestData().stream()
                        .map(row -> Row.of(row.getField(0), row.getField(1), row.getField(2)))
                        .collect(Collectors.toList());

        assertThat(collected).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    public void testLimit() {
        String testTable = "testTable";
        tEnv.executeSql(
                inputTable.getCreateQueryForFlink(
                        getMetadata(),
                        testTable,
                        Arrays.asList(
                                "'scan.partition.column'='id'",
                                "'scan.partition.num'='2'",
                                "'scan.partition.lower-bound'='1'",
                                "'scan.partition.upper-bound'='2'")));

        List<Row> collected = executeQuery("SELECT * FROM " + testTable + " LIMIT 1");

        assertThat(collected).hasSize(1);
        assertThat(getTestData())
                .as("The actual output is not a subset of the expected set.")
                .containsAll(collected);
    }

    @Test
    public void testFilter() {
        String testTable = "testTable";
        tEnv.executeSql(inputTable.getCreateQueryForFlink(getMetadata(), testTable));

        // create a partitioned table to ensure no regression
        String partitionedTable = "PARTITIONED_TABLE";
        tEnv.executeSql(
                inputTable.getCreateQueryForFlink(
                        getMetadata(),
                        partitionedTable,
                        Arrays.asList(
                                "'scan.partition.column'='id'",
                                "'scan.partition.num'='1'",
                                "'scan.partition.lower-bound'='1'",
                                "'scan.partition.upper-bound'='1'")));

        // we create a VIEW here to test column remapping, ie. would filter push down work if we
        // create a view that depends on our source table
        tEnv.executeSql(
                String.format(
                        "CREATE VIEW FAKE_TABLE (idx, %s) as (SELECT * from %s )",
                        Arrays.stream(inputTable.getTableFields())
                                .filter(f -> !f.equals("id"))
                                .collect(Collectors.joining(",")),
                        testTable));

        Row onlyRow1 =
                getTestData().stream()
                        .filter(row -> row.getFieldAs(0).equals(1L))
                        .findAny()
                        .orElseThrow(NullPointerException::new);

        Row onlyRow2 =
                getTestData().stream()
                        .filter(row -> row.getFieldAs(0).equals(2L))
                        .findAny()
                        .orElseThrow(NullPointerException::new);

        List<Row> twoRows = getTestData();

        // test simple filter
        assertThat(executeQuery("SELECT * FROM FAKE_TABLE WHERE idx = 1"))
                .containsExactly(onlyRow1);

        // test TIMESTAMP filter
        assertThat(
                executeQuery(
                        "SELECT * FROM FAKE_TABLE WHERE timestamp6_col = TIMESTAMP '2020-01-01 15:35:00.123456'"))
                .containsExactly(onlyRow1);

        // test the IN operator
        assertThat(
                executeQuery(
                        "SELECT * FROM FAKE_TABLE WHERE 1 = idx AND double_col IN (100.1234, 101.1234)"))
                .containsExactly(onlyRow1);

        // test mixing AND and OR operator
        assertThat(
                executeQuery(
                        "SELECT * FROM FAKE_TABLE WHERE idx = 1 AND double_col = 100.1234 OR double_col = 101.1234"))
                .containsExactlyInAnyOrderElementsOf(twoRows);

        // test mixing AND/OR with parenthesis, and the swapping the operand of equal expression
        assertThat(
                executeQuery(
                        "SELECT * FROM FAKE_TABLE WHERE (2 = idx AND double_col = 100.1234) OR double_col = 101.1234"))
                .containsExactly(onlyRow2);

        // test Greater than, just to make sure we didnt break anything that we cannot pushdown
        assertThat(
                executeQuery(
                        "SELECT * FROM FAKE_TABLE WHERE idx = 2 AND double_col > 100 OR double_col = 101.123"))
                .containsExactly(onlyRow2);

        // One more test of parenthesis
        assertThat(
                executeQuery(
                        "SELECT * FROM FAKE_TABLE WHERE 2 = idx AND (double_col = 100.1234 OR double_col = 102.1234)"))
                .isEmpty();

        assertThat(
                executeQuery(
                        "SELECT * FROM "
                                + partitionedTable
                                + " WHERE id = 2 AND double_col > 100 OR double_col = 101.123"))
                .isEmpty();

        assertThat(
                executeQuery(
                        "SELECT * FROM "
                                + partitionedTable
                                + " WHERE 1 = id AND double_col IN (100.1234, 101.1234)"))
                .containsExactly(onlyRow1);
    }

    @ParameterizedTest
    @EnumSource(Caching.class)
    void testLookupJoin(Caching caching) {
        // Create JDBC lookup table
        List<String> cachingOptions = Collections.emptyList();
        if (caching.equals(Caching.ENABLE_CACHE)) {
            cachingOptions =
                    Arrays.asList(
                            "'lookup.cache.max-rows' = '100'", "'lookup.cache.ttl' = '10min'");
        }
        tEnv.executeSql(
                inputTable.getCreateQueryForFlink(getMetadata(), "jdbc_lookup", cachingOptions));

        // Create and prepare a value source
        String dataId =
                TestValuesTableFactory.registerData(
                        Arrays.asList(
                                Row.of(1L, "Alice"),
                                Row.of(1L, "Alice"),
                                Row.of(2L, "Bob"),
                                Row.of(3L, "Charlie")));
        tEnv.executeSql(
                String.format(
                        "CREATE TABLE value_source ( "
                                + " `id` BIGINT, "
                                + " `name` STRING, "
                                + " `proctime` AS PROCTIME()"
                                + ") WITH ("
                                + " 'connector' = 'values', "
                                + " 'data-id' = '%s'"
                                + ")",
                        dataId));

        if (caching == Caching.ENABLE_CACHE) {
            LookupCacheManager.keepCacheOnRelease(true);
        }

        // Execute lookup join
        try {
            List<Row> collected =
                    executeQuery(
                            "SELECT S.id, S.name, D.id, D.timestamp6_col, D.double_col FROM value_source"
                                    + " AS S JOIN jdbc_lookup for system_time as of S.proctime AS D ON S.id = D.id");

            assertThat(collected).hasSize(3);

            List<Row> expected =
                    Arrays.asList(
                            Row.of(
                                    1L,
                                    "Alice",
                                    1L,
                                    LocalDateTime.parse("2020-01-01T15:35:00.123456"),
                                    100.1234d),
                            Row.of(
                                    1L,
                                    "Alice",
                                    1L,
                                    LocalDateTime.parse("2020-01-01T15:35:00.123456"),
                                    100.1234d),
                            Row.of(
                                    2L,
                                    "Bob",
                                    2L,
                                    LocalDateTime.parse("2020-01-01T15:36:01.123456"),
                                    101.1234d));

            assertThat(collected)
                    .as("The actual output is not a subset of the expected set")
                    .containsAll(expected);

            if (caching == Caching.ENABLE_CACHE) {
                validateCachedValues();
            }
        } finally {
            if (caching == Caching.ENABLE_CACHE) {
                LookupCacheManager.getInstance().checkAllReleased();
                LookupCacheManager.getInstance().clear();
                LookupCacheManager.keepCacheOnRelease(false);
            }
        }
    }

    private void validateCachedValues() {
        // Validate cache
        Map<String, LookupCacheManager.RefCountedCache> managedCaches =
                LookupCacheManager.getInstance().getManagedCaches();
        assertThat(managedCaches).as("There should be only 1 shared cache registered").hasSize(1);
        LookupCache cache = managedCaches.get(managedCaches.keySet().iterator().next()).getCache();
        // jdbc does support project push down, the cached row has been projected
        RowData key1 = GenericRowData.of(1L);
        RowData value1 =
                GenericRowData.of(
                        1L,
                        100.1234d,
                        TimestampData.fromLocalDateTime(
                                LocalDateTime.parse("2020-01-01T15:35:00.123456")));

        RowData key2 = GenericRowData.of(2L);
        RowData value2 =
                GenericRowData.of(
                        2L,
                        101.1234d,
                        TimestampData.fromLocalDateTime(
                                LocalDateTime.parse("2020-01-01T15:36:01.123456")));

        RowData key3 = GenericRowData.of(3L);

        Map<RowData, Collection<RowData>> expectedEntries = new HashMap<>();
        expectedEntries.put(key1, Collections.singletonList(value1));
        expectedEntries.put(key2, Collections.singletonList(value2));
        expectedEntries.put(key3, Collections.emptyList());

        LookupCacheAssert.assertThat(cache).containsExactlyEntriesOf(expectedEntries);
    }

    private enum Caching {
        ENABLE_CACHE,
        DISABLE_CACHE
    }

    @ParameterizedTest
    @EnumSource(Caching.class)
    void testLookupJoinOnColumnsWithSpecialCharacters(Caching caching) throws Exception {
        // Elasticsearch column names commonly contain characters that are not valid in JDBC
        // named parameters ('.', '@', ':', '?'). The lookup query uses positional parameters.
        String indexName = "lookup_special_character_columns";
        client.createIndex(
                indexName,
                """
                {"mappings": {"properties": {
                  "order.details.referenceId": {"type": "keyword"},
                  "user.name": {"type": "keyword"},
                  "order.details.attributes.productName": {"type": "keyword"},
                  "@version": {"type": "integer"},
                  "geo:loc": {"type": "keyword"},
                  "a\\"b": {"type": "keyword"},
                  "what?": {"type": "keyword"}
                }}}""");
        try {
            // bulk API: one JSON document per line
            client.addDataBulk(
                    indexName,
                    """
                    {"index": {"_id": "1"}}
                    {"order": {"details": {"referenceId": "ref-aaaa", \
                    "attributes": {"productName": "Product One"}}}, \
                    "user": {"name": "alice"}, "@version": 1, \
                    "geo:loc": "loc-1", "a\\"b": "loc-1", "what?": "yes"}
                    {"index": {"_id": "2"}}
                    {"order.details.referenceId": "ref-bbbb", \
                    "order.details.attributes.productName": "Product Two", \
                    "user.name": "bob", "@version": 2, \
                    "geo:loc": "loc-2", "a\\"b": "loc-2", "what?": "yes"}
                    {"index": {"_id": "3"}}
                    {"order.details.referenceId": "ref-bbbb", \
                    "order.details.attributes.productName": "Product Three", \
                    "user.name": "carol", "@version": 3, \
                    "geo:loc": "loc-2", "a\\"b": "loc-2", "what?": "no"}
                    """);

            String cachingOptions =
                    caching == Caching.ENABLE_CACHE
                            ? ", 'lookup.cache' = 'PARTIAL', 'lookup.partial-cache.max-rows' = '100'"
                            : "";
            tEnv.executeSql(
                    """
                    CREATE TABLE es_lookup (
                      `order.details.referenceId` STRING,
                      `user.name` STRING,
                      `order.details.attributes.productName` STRING,
                      `@version` INT,
                      `geo:loc` STRING,
                      `a"b` STRING,
                      `what?` STRING
                    ) WITH (
                      'connector' = '%s',
                      'url' = '%s',
                      'table-name' = '%s',
                      'username' = '%s',
                      'password' = '%s'
                      %s
                    )""".formatted(
                            ElasticsearchDynamicTableFactory.IDENTIFIER,
                            getMetadata().getJdbcUrl(),
                            indexName,
                            getMetadata().getUsername(),
                            getMetadata().getPassword(),
                            cachingOptions));

            String dataId =
                    TestValuesTableFactory.registerData(
                            List.of(
                                    Row.of("REF-AAAA", "alice", "loc-1", 1),
                                    Row.of("REF-BBBB", "bob", "loc-2", 2),
                                    Row.of("REF-BBBB", "carol", "loc-2", 3),
                                    // no matching document
                                    Row.of("REF-CCCC", "dave", "loc-9", 9),
                                    // null lookup keys
                                    Row.of(null, "eve", null, null)));
            tEnv.executeSql(
                    """
                    CREATE TABLE events (
                      `event.ReferenceId` STRING,
                      `user` STRING,
                      `location` STRING,
                      `version` INT,
                      `proctime` AS PROCTIME()
                    ) WITH (
                      'connector' = 'values',
                      'data-id' = '%s'
                    )""".formatted(dataId));

            if (caching == Caching.ENABLE_CACHE) {
                LookupCacheManager.keepCacheOnRelease(true);
            }
            try {
                // single dotted key, function applied on the probe side
                assertThat(
                                executeQuery(
                                        """
                                        SELECT A.`event.ReferenceId`, D.`order.details.attributes.productName`
                                        FROM events AS A
                                        JOIN es_lookup FOR SYSTEM_TIME AS OF A.proctime AS D
                                          ON D.`order.details.referenceId` = LOWER(A.`event.ReferenceId`)
                                        """))
                        .containsExactlyInAnyOrder(
                                Row.of("REF-AAAA", "Product One"),
                                Row.of("REF-BBBB", "Product Two"),
                                Row.of("REF-BBBB", "Product Three"),
                                Row.of("REF-BBBB", "Product Two"),
                                Row.of("REF-BBBB", "Product Three"));

                // two keys with special characters and a range filter on the lookup table
                assertThat(
                                executeQuery(
                                        """
                                        SELECT A.`user`, D.`@version`
                                        FROM events AS A
                                        JOIN es_lookup FOR SYSTEM_TIME AS OF A.proctime AS D
                                          ON D.`order.details.referenceId` = LOWER(A.`event.ReferenceId`)
                                          AND D.`user.name` = A.`user`
                                        WHERE D.`@version` > 1
                                        """))
                        .containsExactlyInAnyOrder(Row.of("bob", 2), Row.of("carol", 3));

                // key with ':' and a selected column with '?'
                assertThat(
                                executeQuery(
                                        """
                                        SELECT A.`user`, D.`what?`
                                        FROM events AS A
                                        JOIN es_lookup FOR SYSTEM_TIME AS OF A.proctime AS D
                                          ON D.`geo:loc` = A.`location`
                                        """))
                        .containsExactlyInAnyOrder(
                                Row.of("alice", "yes"),
                                Row.of("bob", "yes"),
                                Row.of("bob", "no"),
                                Row.of("carol", "yes"),
                                Row.of("carol", "no"));

                // keys with ':' and '.' followed by a filter on a column with '?':
                // key parameters must be bound before the filter parameter
                assertThat(
                                executeQuery(
                                        """
                                        SELECT A.`user`, D.`order.details.attributes.productName`
                                        FROM events AS A
                                        JOIN es_lookup FOR SYSTEM_TIME AS OF A.proctime AS D
                                          ON D.`geo:loc` = A.`location`
                                          AND D.`order.details.referenceId` = LOWER(A.`event.ReferenceId`)
                                        WHERE D.`what?` <> 'no'
                                        """))
                        .containsExactlyInAnyOrder(
                                Row.of("alice", "Product One"),
                                Row.of("bob", "Product Two"),
                                Row.of("carol", "Product Two"));

                // INT key followed by two filters: filter parameters keep their order
                assertThat(
                                executeQuery(
                                        """
                                        SELECT A.`user`, D.`what?`, D.`geo:loc`
                                        FROM events AS A
                                        JOIN es_lookup FOR SYSTEM_TIME AS OF A.proctime AS D
                                          ON D.`@version` = A.`version`
                                        WHERE D.`what?` <> 'yes' AND D.`@version` < 10
                                        """))
                        .containsExactly(Row.of("carol", "no", "loc-2"));

                // key with a double quote, escaped by the dialect as ""
                assertThat(
                                executeQuery(
                                        """
                                        SELECT A.`user`, D.`what?`, D.`a"b`
                                        FROM events AS A
                                        JOIN es_lookup FOR SYSTEM_TIME AS OF A.proctime AS D
                                          ON D.`a"b` = A.`location`
                                        WHERE D.`what?` <> 'no'
                                        """))
                        .containsExactlyInAnyOrder(
                                Row.of("alice", "yes", "loc-1"),
                                Row.of("bob", "yes", "loc-2"),
                                Row.of("carol", "yes", "loc-2"));

                // scan with a pushed down filter on a column with a double quote
                assertThat(
                                executeQuery(
                                        """
                                        SELECT `order.details.attributes.productName`, `a"b`
                                        FROM es_lookup
                                        WHERE `a"b` = 'loc-2'
                                        """))
                        .containsExactlyInAnyOrder(
                                Row.of("Product Two", "loc-2"), Row.of("Product Three", "loc-2"));

                // left join: rows without a match and with null keys are kept
                assertThat(
                                executeQuery(
                                        """
                                        SELECT A.`user`, D.`what?`
                                        FROM events AS A
                                        LEFT JOIN es_lookup FOR SYSTEM_TIME AS OF A.proctime AS D
                                          ON D.`geo:loc` = A.`location`
                                        """))
                        .containsExactlyInAnyOrder(
                                Row.of("alice", "yes"),
                                Row.of("bob", "yes"),
                                Row.of("bob", "no"),
                                Row.of("carol", "yes"),
                                Row.of("carol", "no"),
                                Row.of("dave", null),
                                Row.of("eve", null));
            } finally {
                if (caching == Caching.ENABLE_CACHE) {
                    LookupCacheManager.getInstance().clear();
                    LookupCacheManager.keepCacheOnRelease(false);
                }
            }
        } finally {
            client.deleteIndex(indexName);
        }
    }

    private List<Row> executeQuery(String query) {
        return CollectionUtil.iteratorToList(tEnv.executeSql(query).collect());
    }

    private TableRow createInputTable() {
        return tableRow(
                "jdbc_dynamic_table_source",
                field("id", DataTypes.BIGINT().notNull()),
                field("double_col", DataTypes.DOUBLE()),
                field("timestamp6_col", DataTypes.TIMESTAMP(6)),
                field("boolean_col", DataTypes.BOOLEAN()),
                field("tinyint_col", DataTypes.TINYINT()),
                field("smallint_col", DataTypes.SMALLINT()),
                field("integer_col", DataTypes.INT()),
                field("bigint_col", DataTypes.BIGINT()),
                field("float_col", DataTypes.FLOAT()),
                field("varchar15_col", DataTypes.VARCHAR(15)),
                field("date_col", DataTypes.DATE()));
    }

    protected List<Row> getTestData() {
        return Arrays.asList(
                Row.of(
                        1L,
                        100.1234d,
                        LocalDateTime.parse("2020-01-01T15:35:00.123456"),
                        true,
                        (byte) -12,
                        (short) 31_987,
                        1_147_483_647,
                        8_223_372_036_854_775_807L,
                        70.5f,
                        "some-text",
                        LocalDate.parse("2020-01-01")),
                Row.of(
                        2L,
                        101.1234d,
                        LocalDateTime.parse("2020-01-01T15:36:01.123456"),
                        false,
                        (byte) 23,
                        (short) -29_987,
                        -2_047_483_647,
                        -1_223_372_036_854_775_807L,
                        -123.5f,
                        "other-text",
                        LocalDate.parse("2020-01-02")));
    }
}
