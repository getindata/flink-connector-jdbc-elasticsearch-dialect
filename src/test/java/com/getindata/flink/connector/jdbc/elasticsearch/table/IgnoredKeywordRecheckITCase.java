package com.getindata.flink.connector.jdbc.elasticsearch.table;

import com.getindata.flink.connector.jdbc.elasticsearch.ElasticsearchTestBase;
import com.getindata.flink.connector.jdbc.elasticsearch.database.catalog.ElasticsearchCatalog;
import com.getindata.flink.connector.jdbc.elasticsearch.testutils.ElasticsearchDatabase;
import com.getindata.flink.connector.jdbc.elasticsearch.testutils.ElasticsearchMetadata;
import com.getindata.flink.connector.jdbc.elasticsearch.testutils.ElasticsearchRestClient;
import com.getindata.flink.connector.jdbc.elasticsearch.testutils.FlinkTables;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.apache.flink.table.runtime.functions.table.lookup.LookupCacheManager;
import org.apache.flink.test.util.AbstractTestBase;
import org.apache.flink.types.Row;
import org.apache.flink.util.ExceptionUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class IgnoredKeywordRecheckITCase extends AbstractTestBase implements ElasticsearchTestBase {

    private static final String INDEX = "ignored_keyword_recheck";
    private static final String MULTI_VALUE_INDEX = "ignored_keyword_recheck_multi_value";
    private static final String CLIENT = "some client long value";
    private static final String ZZZ = "zzz long value here";
    private static final String COLUMNS = "id INT, text_kw STRING, text_raw STRING, name STRING, kw STRING, two_kw STRING";

    private static ElasticsearchRestClient client;

    private TableEnvironment tEnv;

    @BeforeAll
    static void createIndex() throws Exception {
        client = new ElasticsearchRestClient(ElasticsearchDatabase.getMetadata());
        client.createIndex(INDEX, """
                {"settings": {"analysis": {"normalizer": {"lc": {"type": "custom", "filter": ["lowercase"]}}}},
                 "mappings": {"properties": {
                  "id": {"type": "integer"},
                  "text_kw": {"type": "text", "fields": {"keyword": {"type": "keyword", "ignore_above": 10}}},
                  "text_raw": {"type": "text", "fields": {"raw": {"type": "keyword", "ignore_above": 10}}},
                  "name": {"type": "text", "fields": {"keyword": {"type": "keyword", "normalizer": "lc"}}},
                  "kw": {"type": "keyword", "ignore_above": 10},
                  "two_kw": {"type": "text", "fields": {
                   "keyword": {"type": "keyword", "ignore_above": 10},
                   "raw": {"type": "keyword", "ignore_above": 30}}}
                }}}""");
        client.addDataBulk(INDEX, """
                {"index": {"_id": "1"}}
                {"id": 1, "text_kw": "short", "text_raw": "short", "kw": "short", "name": "A", "two_kw": "short"}
                {"index": {"_id": "2"}}
                {"id": 2, "text_kw": "%1$s", "text_raw": "%1$s", "kw": "%1$s", "name": "a", "two_kw": "%1$s"}
                {"index": {"_id": "3"}}
                {"id": 3, "text_kw": "%2$s", "text_raw": "%2$s", "kw": "%2$s", "name": "bAb"}
                {"index": {"_id": "4"}}
                {"id": 4, "name": "xyz"}
                {"index": {"_id": "5"}}
                {"id": 5, "text_kw": "abc", "text_raw": "abc", "kw": "abc"}
                """.formatted(CLIENT, ZZZ));
        client.createIndex(MULTI_VALUE_INDEX, """
                {"mappings": {"properties": {
                  "id": {"type": "integer"},
                  "tags": {"type": "text", "fields": {"keyword": {"type": "keyword", "ignore_above": 10}}}
                }}}""");
        client.addDataBulk(MULTI_VALUE_INDEX, """
                {"index": {"_id": "1"}}
                {"id": 1, "tags": ["x", "y"]}
                """);
    }

    @AfterAll
    static void deleteIndex() throws Exception {
        if (client != null) {
            client.deleteIndex(INDEX);
            client.deleteIndex(MULTI_VALUE_INDEX);
        }
    }

    @BeforeEach
    void setUp() {
        tEnv = StreamTableEnvironment.create(StreamExecutionEnvironment.getExecutionEnvironment());
        createTable(tEnv, "docs", "elasticsearch-jdbc", "");
        createTable(tEnv, "docs_flink", "elasticsearch-jdbc", ", 'filter.handling.policy' = 'never'");
        createTable(tEnv, "docs_off", "elasticsearch-jdbc", ", 'filter.ignored-keyword.handling' = 'none'");
    }

    @ParameterizedTest
    @ValueSource(strings = {"text_kw", "text_raw"})
    void recheckShouldGiveTheSameResultAsFilteringInFlink(String column) {
        var conditions = List.of(
                "%s = '" + CLIENT + "'",
                "%s <> 'short'",
                "%s < 'b'",
                "%s <= 'short'",
                "%s > 'short'",
                "%s >= 'short'",
                "%s LIKE '%%client%%'",
                "%s LIKE '%%long%%'",
                "%s IS NULL",
                "%s IS NOT NULL",
                "%s LIKE '%%long%%' OR id = 1",
                "%s = '" + ZZZ + "' AND id > 1",
                "(%s LIKE '%%client%%' OR id = 5) AND id < 5",
                // the planner rewrites negations or keeps them in Flink, never above an extended condition
                "NOT (%s LIKE '%%client%%')",
                "%s NOT LIKE '%%long%%'",
                "NOT (%s = 'short')",
                "NOT (%s <> 'short')",
                "NOT (%s = 'short' OR id = 3)",
                "NOT (%s LIKE '%%long%%' AND id > 1)",
                "NOT (%s LIKE '%%long%%' OR %s IS NULL)",
                "NOT (NOT (%s LIKE '%%client%%'))",
                "NOT (%s IS NULL)",
                "NOT (%s > 'short')",
                "%s IN ('short', '" + CLIENT + "')",
                "%s NOT IN ('short', 'abc')",
                "%s BETWEEN 'b' AND 't'",
                "%s NOT BETWEEN 'b' AND 't'",
                "(%s LIKE '%%client%%') IS NOT TRUE",
                "(%s LIKE '%%client%%') IS FALSE",
                "NOT (%s LIKE '%%client%%') AND id <> 4",
                "CASE WHEN %s LIKE '%%long%%' THEN FALSE ELSE TRUE END");
        var select = "SELECT id, %s FROM %s WHERE %s";
        for (var condition : conditions) {
            var where = condition.formatted(column, column);
            var expected = query(select.formatted(column, "docs_flink", where));

            assertThat(query(select.formatted(column, "docs", where)))
                    .as(where)
                    .containsExactlyInAnyOrderElementsOf(expected);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"text_kw", "text_raw"})
    void recheckShouldFindDocumentsWithIgnoredSubfield(String column) {
        assertThat(query("SELECT id, %1$s FROM docs WHERE %1$s LIKE '%%client%%'".formatted(column)))
                .containsExactly(Row.of(2, CLIENT));
        assertThat(query("SELECT id FROM docs WHERE %s = '%s'".formatted(column, ZZZ)))
                .containsExactly(Row.of(3));
        assertThat(query("SELECT id FROM docs WHERE %s > 'short'".formatted(column)))
                .containsExactlyInAnyOrder(Row.of(2), Row.of(3));
    }

    @ParameterizedTest
    @ValueSource(strings = {"text_kw", "text_raw"})
    void disabledRecheckShouldKeepTheOldResult(String column) {
        assertThat(query("SELECT id FROM docs_off WHERE %s LIKE '%%client%%'".formatted(column))).isEmpty();
        assertThat(query("SELECT id FROM docs_off WHERE %s = '%s'".formatted(column, ZZZ))).isEmpty();
        // ranges are not evaluated on the keyword subfield, they do not lose these documents
    }

    @Test
    void recheckShouldFollowFlinkSemanticsForNormalizer() {
        assertThat(query("SELECT id, name FROM docs WHERE name LIKE '%a%'"))
                .containsExactly(Row.of(2, "a"));
        assertThat(query("SELECT id, name FROM docs WHERE name = 'a'"))
                .containsExactly(Row.of(2, "a"));

        // without the recheck Elasticsearch compares case insensitively and Flink replaces
        // name with the literal of the condition
        assertThat(query("SELECT id, name FROM docs_off WHERE name LIKE '%a%'"))
                .containsExactlyInAnyOrder(Row.of(1, "A"), Row.of(2, "a"), Row.of(3, "bAb"));
        assertThat(query("SELECT id, name FROM docs_off WHERE name = 'a'"))
                .containsExactlyInAnyOrder(Row.of(1, "a"), Row.of(2, "a"));
    }

    @Test
    void keywordColumnShouldNotBeAffected() {
        var sql = "SELECT id, kw FROM docs WHERE kw LIKE '%o%'";
        var explainOn = tEnv.explainSql(sql);
        var rowsOn = query(sql);

        tEnv.getConfig().set("elasticsearch-jdbc.filter.ignored-keyword.handling", "none");

        assertThat(tEnv.explainSql(sql)).isEqualTo(explainOn).doesNotContain("Calc(");
        assertThat(query(sql)).isEqualTo(rowsOn).containsExactly(Row.of(1, "short"));
    }

    @Test
    void filterOnColumnWithSeveralKeywordSubfieldsShouldFailInsteadOfLosingDocuments() {
        var failure = catchThrowable(() -> query("SELECT id FROM docs WHERE two_kw LIKE '%client%'"));

        assertThat(ExceptionUtils.findThrowableWithMessage(failure, "Multiple exact keyword candidates")).isPresent();
        assertThat(query("SELECT id FROM docs WHERE two_kw IS NULL"))
                .containsExactlyInAnyOrder(Row.of(3), Row.of(4), Row.of(5));
    }

    @Test
    void recheckShouldBeVisibleInPlan() {
        assertThat(tEnv.explainSql("SELECT id FROM docs WHERE text_kw LIKE '%client%'"))
                .contains("filter=[LIKE(text_kw, _UTF-16LE'%client%')]")
                .contains("where=[LIKE(text_kw, _UTF-16LE'%client%')]");
    }

    @Test
    void disabledRecheckShouldGiveTheSamePlanAsJdbcConnector() {
        TableEnvironment jdbcEnv =
                StreamTableEnvironment.create(StreamExecutionEnvironment.getExecutionEnvironment());
        createTable(jdbcEnv, "docs_off", "jdbc", "");
        var sql = "SELECT id, text_kw FROM docs_off WHERE text_kw LIKE '%client%' AND id > 1";

        assertThat(tEnv.explainSql(sql)).isEqualTo(jdbcEnv.explainSql(sql));
    }

    @Test
    void recheckShouldBeConfigurable() {
        var filter = " WHERE text_kw LIKE '%client%'";
        var hintOff = " /*+ OPTIONS('filter.ignored-keyword.handling'='none') */";
        var hintOn = " /*+ OPTIONS('filter.ignored-keyword.handling'='recheck') */";

        assertThat(query("SELECT id FROM docs" + filter)).containsExactly(Row.of(2));
        assertThat(query("SELECT id FROM docs_off" + filter)).isEmpty();
        assertThat(query("SELECT id FROM docs_off" + hintOn + filter)).containsExactly(Row.of(2));
        assertThat(query("SELECT id FROM docs" + hintOff + filter)).isEmpty();

        tEnv.getConfig().set("elasticsearch-jdbc.filter.ignored-keyword.handling", "none");
        assertThat(query("SELECT id FROM docs" + filter)).isEmpty();
        assertThat(query("SELECT id FROM docs" + hintOn + filter)).containsExactly(Row.of(2));
        createTable(tEnv, "docs_on", "elasticsearch-jdbc", ", 'filter.ignored-keyword.handling' = 'recheck'");
        assertThat(query("SELECT id FROM docs_on" + filter)).containsExactly(Row.of(2));
    }

    @Test
    void recheckShouldWorkForCatalogTables() {
        var metadata = (ElasticsearchMetadata) getMetadata();
        tEnv.registerCatalog(
                "es",
                new ElasticsearchCatalog(
                        getClass().getClassLoader(),
                        "es",
                        "docker-cluster",
                        metadata.getUsername(),
                        metadata.getPassword(),
                        metadata.getJdbcUrl()));
        var sql = "SELECT id FROM es.`docker-cluster`.%s%s WHERE text_kw LIKE '%%client%%'";
        var hintOff = " /*+ OPTIONS('filter.ignored-keyword.handling'='none') */";

        assertThat(query(sql.formatted(INDEX, ""))).containsExactly(Row.of(2));
        assertThat(query(sql.formatted(INDEX, hintOff))).isEmpty();
        tEnv.getConfig().set("elasticsearch-jdbc.filter.ignored-keyword.handling", "none");
        assertThat(query(sql.formatted(INDEX, ""))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lookupShouldFindDocumentsWithIgnoredKey(boolean cache) {
        var cacheOptions = FlinkTables.partialCacheOptions(cache);
        createTable(tEnv, "lookup_on", "elasticsearch-jdbc", cacheOptions);
        createTable(tEnv, "lookup_off", "elasticsearch-jdbc",
                cacheOptions + ", 'filter.ignored-keyword.handling' = 'none'");
        createProbe(Row.of(1, CLIENT), Row.of(2, "short"), Row.of(3, "missing"), Row.of(4, CLIENT), Row.of(5, null));
        var join = """
                SELECT P.pid, D.id
                FROM probe AS P
                JOIN %s FOR SYSTEM_TIME AS OF P.proctime AS D ON D.text_kw = P.pkey""";

        if (cache) {
            LookupCacheManager.keepCacheOnRelease(true);
        }
        try {
            // "short" also fetches all documents with an ignored subfield, they are dropped
            assertThat(query(join.formatted("lookup_on")))
                    .containsExactlyInAnyOrder(Row.of(1, 2), Row.of(2, 1), Row.of(4, 2));
            assertThat(query(join.formatted("lookup_off"))).containsExactly(Row.of(2, 1));
        } finally {
            if (cache) {
                LookupCacheManager.getInstance().clear();
                LookupCacheManager.keepCacheOnRelease(false);
            }
        }
    }

    @Test
    void lookupShouldCheckPushedDownFilterAgain() {
        createProbe(Row.of(1, "x"), Row.of(2, "x"), Row.of(3, "x"), Row.of(5, "x"));
        var join = """
                SELECT P.pid, D.text_kw
                FROM probe AS P
                JOIN %s FOR SYSTEM_TIME AS OF P.proctime AS D ON D.id = P.pid
                WHERE D.text_kw LIKE '%%client%%'""";

        // document 3 matches the extended condition in Elasticsearch, Flink drops it
        assertThat(query(join.formatted("docs"))).containsExactly(Row.of(2, CLIENT));
        assertThat(query(join.formatted("docs_off"))).isEmpty();
    }

    @Test
    void recheckShouldFailOnMultiValuedFieldInsteadOfDroppingRows() {
        elasticsearchTable("tags", "id INT, tags STRING", MULTI_VALUE_INDEX).build().createIn(tEnv);
        var sql = "SELECT id, tags FROM tags%s WHERE tags LIKE 'y%%'";

        var failure = catchThrowable(() -> query(sql.formatted("")));

        assertThat(ExceptionUtils.findThrowableWithMessage(failure, "Arrays (returned by [tags]) are not supported"))
                .isPresent();
        assertThat(query(sql.formatted(" /*+ OPTIONS('filter.ignored-keyword.handling'='none') */")))
                .containsExactly(Row.of(1, "x"));
    }

    private void createProbe(Row... rows) {
        FlinkTables.createLookupProbeTable(tEnv, "probe", "pid INT, pkey STRING", List.of(rows));
    }

    private void createTable(TableEnvironment env, String name, String connector, String extraOptions) {
        elasticsearchTable(name, COLUMNS, INDEX).connector(connector).options(extraOptions).build().createIn(env);
    }

    private List<Row> query(String sql) {
        return FlinkTables.collect(tEnv, sql);
    }
}
