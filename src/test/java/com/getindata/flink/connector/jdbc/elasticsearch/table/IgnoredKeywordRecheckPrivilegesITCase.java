package com.getindata.flink.connector.jdbc.elasticsearch.table;

import com.getindata.flink.connector.jdbc.elasticsearch.ElasticsearchTestBase;
import com.getindata.flink.connector.jdbc.elasticsearch.testutils.ElasticsearchDatabase;
import com.getindata.flink.connector.jdbc.elasticsearch.testutils.ElasticsearchRestClient;
import com.getindata.flink.connector.jdbc.elasticsearch.testutils.FlinkTables;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.apache.flink.test.util.AbstractTestBase;
import org.apache.flink.types.Row;
import org.apache.flink.util.ExceptionUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class IgnoredKeywordRecheckPrivilegesITCase extends AbstractTestBase implements ElasticsearchTestBase {

    private static final String INDEX = "recheck_privileges";
    private static final String PASSWORD = "restricted-password";
    private static final String OK = "ok";

    private static ElasticsearchRestClient client;

    // name, cluster and index privileges, expected SELECT outcome
    static Stream<Arguments> roles() {
        return Stream.of(
                Arguments.of("read_only", "[]", "[\"read\"]",
                        "action [cluster:monitor/main] is unauthorized"),
                Arguments.of("main_read", "[\"cluster:monitor/main\"]", "[\"read\"]", OK),
                Arguments.of("main_search_field_caps", "[\"cluster:monitor/main\"]",
                        "[\"indices:data/read/search\", \"indices:data/read/field_caps\"]",
                        "action [indices:data/read/sql] is unauthorized"));
    }

    @BeforeAll
    static void setUp() throws Exception {
        client = new ElasticsearchRestClient(ElasticsearchDatabase.getMetadata());
        client.createIndex(INDEX, """
                {"mappings": {"properties": {
                  "id": {"type": "integer"},
                  "text_kw": {"type": "text", "fields": {"keyword": {"type": "keyword", "ignore_above": 10}}}
                }}}""");
        client.addDataBulk(INDEX, """
                {"index": {"_id": "1"}}
                {"id": 1, "text_kw": "short"}
                {"index": {"_id": "2"}}
                {"id": 2, "text_kw": "some client long value"}
                """);
        for (var role : roles().toList()) {
            var args = role.get();
            client.performRequest("PUT", "/_security/role/" + args[0], """
                    {"cluster": %s, "indices": [{"names": ["%s"], "privileges": %s}]}"""
                    .formatted(args[1], INDEX, args[2]));
            client.performRequest("PUT", "/_security/user/" + args[0], """
                    {"password": "%s", "roles": ["%s"]}"""
                    .formatted(PASSWORD, args[0]));
        }
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (client != null) {
            for (var role : roles().toList()) {
                client.performRequest("DELETE", "/_security/user/" + role.get()[0], null);
                client.performRequest("DELETE", "/_security/role/" + role.get()[0], null);
            }
            client.deleteIndex(INDEX);
            client.close();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("roles")
    void recheckShouldNeedTheSamePrivilegesAsSelect(
            String user, String cluster, String indices, String expectedSelect) {
        var select = outcome(() -> {
            try (var connection = connect(user);
                 var statement = connection.createStatement();
                 var resultSet = statement.executeQuery("SELECT id FROM " + INDEX)) {
                var ids = new ArrayList<Integer>();
                while (resultSet.next()) {
                    ids.add(resultSet.getInt(1));
                }
                assertThat(ids).containsExactlyInAnyOrder(1, 2);
            }
        });
        assertThat(select).contains(expectedSelect);

        var getColumns = outcome(() -> {
            try (var connection = connect(user)) {
                assertThat(EsColumnMetadata.readTextColumnsWithKeywordSubfield(
                        connection.getMetaData(), INDEX))
                        .isEqualTo(Map.of("text_kw", "text_kw.keyword"));
            }
        });
        var recheckOn = outcome(() -> assertThat(flinkQuery(user, "true")).containsExactly(Row.of(2)));
        var recheckOff = outcome(() -> assertThat(flinkQuery(user, "false")).isEmpty());

        assertThat(List.of(getColumns, recheckOn, recheckOff)).containsOnly(select);
    }

    private interface Action {
        void run() throws Exception;
    }

    private static String outcome(Action action) {
        try {
            action.run();
            return OK;
        } catch (AssertionError e) {
            throw e;
        } catch (Exception e) {
            return ExceptionUtils.findThrowable(e, SQLException.class)
                    .map(Throwable::getMessage)
                    .orElseGet(() -> ExceptionUtils.stringifyException(e));
        }
    }

    private List<Row> flinkQuery(String user, String recheck) {
        TableEnvironment tEnv =
                StreamTableEnvironment.create(StreamExecutionEnvironment.getExecutionEnvironment());
        elasticsearchTable("docs", "id INT, text_kw STRING", INDEX)
                .username(user)
                .password(PASSWORD)
                .options(", 'filter.ignored-keyword.recheck' = '%s'".formatted(recheck))
                .build()
                .createIn(tEnv);
        return FlinkTables.collect(tEnv, "SELECT id FROM docs WHERE text_kw LIKE '%client%'");
    }

    private Connection connect(String user) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), user, PASSWORD);
    }

    private String jdbcUrl() {
        return getMetadata().getJdbcUrl();
    }
}
