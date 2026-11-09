package com.getindata.flink.connector.jdbc.elasticsearch.table;

import com.getindata.flink.connector.jdbc.elasticsearch.database.dialect.ElasticsearchDialect;
import org.apache.flink.connector.jdbc.internal.options.InternalJdbcConnectionOptions;
import org.apache.flink.connector.jdbc.statement.FieldNamedPreparedStatement;
import org.apache.flink.connector.jdbc.statement.FieldNamedPreparedStatementImpl;
import org.apache.flink.streaming.api.lineage.LineageVertex;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.data.GenericRowData;
import org.apache.flink.table.data.StringData;
import org.apache.flink.table.data.TimestampData;
import org.apache.flink.table.types.DataType;
import org.apache.flink.table.types.logical.RowType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;

import java.io.Serializable;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class ElasticsearchRowDataLookupFunctionTest {

    private static final String TABLE_NAME = "idx-orders.details-test";
    private static final String REFERENCE_ID = "order.details.referenceId";
    private static final String PRODUCT_NAME = "order.details.attributes.productName";
    private static final String TIMESTAMP = "@timestamp";
    private static final String VERSION = "@version";
    private static final String GEO = "geo:loc";
    private static final String QUESTION = "what?";

    private final ElasticsearchDialect dialect = new ElasticsearchDialect();

    private Connection connection;
    private PreparedStatement preparedStatement;

    @BeforeEach
    void setUp() throws Exception {
        connection = mock(Connection.class);
        preparedStatement = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
    }

    // --- query building

    @Test
    void shouldBuildQueryWithPositionalParameterForEveryKey() {
        String query =
                ElasticsearchRowDataLookupFunction.buildLookupQuery(
                        dialect,
                        TABLE_NAME,
                        List.of(REFERENCE_ID, PRODUCT_NAME, TIMESTAMP),
                        lookupKeyConditions(List.of(REFERENCE_ID, TIMESTAMP)));

        assertThat(query)
                .isEqualTo("""
                        SELECT "order.details.referenceId", "order.details.attributes.productName", "@timestamp" \
                        FROM "idx-orders.details-test" \
                        WHERE "order.details.referenceId" = ? \
                        AND "@timestamp" = ?""");
    }

    @Test
    void shouldBuildQueryWithoutWhereClauseWhenThereAreNoKeys() {
        String query =
                ElasticsearchRowDataLookupFunction.buildLookupQuery(
                        dialect, TABLE_NAME, List.of(REFERENCE_ID), List.of());

        assertThat(query)
                .isEqualTo("SELECT \"order.details.referenceId\" FROM \"idx-orders.details-test\"");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "order.details.referenceId",
                "@timestamp",
                "geo:loc",
                "what?",
                "a:b?c",
                ":leading",
                "trailing?",
                "with space",
                "it's"
            })
    void shouldKeepColumnNameUnchangedInQuery(String columnName) {
        String query =
                ElasticsearchRowDataLookupFunction.buildLookupQuery(
                        dialect, "idx", List.of(columnName), lookupKeyConditions(List.of(columnName)));

        assertThat(query)
                .isEqualTo("""
                        SELECT "%1$s" FROM "idx" WHERE "%1$s" = ?""".formatted(columnName));
    }

    @Test
    void shouldKeepCrossClusterTableNameUnchangedInQuery() {
        String query =
                ElasticsearchRowDataLookupFunction.buildLookupQuery(
                        dialect, "remote:idx", List.of(GEO), lookupKeyConditions(List.of(GEO)));

        assertThat(query).isEqualTo("SELECT \"geo:loc\" FROM \"remote:idx\" WHERE \"geo:loc\" = ?");
    }

    @Test
    void shouldEscapeDoubleQuoteInKeyAndSelectedColumns() {
        String query =
                ElasticsearchRowDataLookupFunction.buildLookupQuery(
                        dialect, "idx", List.of("a\"b", GEO), lookupKeyConditions(List.of("a\"b")));

        assertThat(query)
                .isEqualTo("SELECT \"a\"\"b\", \"geo:loc\" FROM \"idx\" WHERE \"a\"\"b\" = ?");
    }

    // --- statement preparation

    @Test
    void shouldAppendPushedDownPredicatesAfterKeys() throws Exception {
        ElasticsearchRowDataLookupFunction function =
                function(
                        TABLE_NAME,
                        List.of(REFERENCE_ID, VERSION),
                        List.of("\"order.details.attributes.productName\" = ?", "\"@version\" > ?"),
                        "Product X",
                        1);

        function.prepareStatement(connection);

        verify(connection)
                .prepareStatement("""
                        SELECT "order.details.referenceId", "order.details.attributes.productName", "@version" \
                        FROM "idx-orders.details-test" \
                        WHERE "order.details.referenceId" = ? AND "@version" = ? \
                        AND ("order.details.attributes.productName" = ?) \
                        AND ("@version" > ?)""");
    }

    @Test
    void shouldStartWhereClauseWithPredicatesWhenThereAreNoKeys() throws Exception {
        ElasticsearchRowDataLookupFunction function =
                function(
                        TABLE_NAME,
                        List.of(),
                        List.of("\"@version\" > ?"),
                        1);

        function.prepareStatement(connection);

        verify(connection)
                .prepareStatement("""
                        SELECT "order.details.referenceId", "order.details.attributes.productName", "@version" \
                        FROM "idx-orders.details-test" \
                        WHERE ("@version" > ?)""");
    }

    @Test
    void shouldNotAppendAnythingWithoutPredicates() throws Exception {
        ElasticsearchRowDataLookupFunction function =
                function(TABLE_NAME, List.of(REFERENCE_ID), List.of());

        function.prepareStatement(connection);

        verify(connection).prepareStatement(function.getQuery());
    }

    @Test
    void shouldPassSpecialCharactersToDriverWithoutParsing() throws Exception {
        ElasticsearchRowDataLookupFunction function =
                specialCharacterFunction(
                        "remote:idx", List.of("\"what?\" = ?"), "x");

        FieldNamedPreparedStatement statement = function.prepareStatement(connection);

        String expectedSql = """
                SELECT "geo:loc", "what?" FROM "remote:idx" \
                WHERE "geo:loc" = ? AND ("what?" = ?)""";
        assertThat(statement).isInstanceOf(PositionalPreparedStatement.class);
        assertThat(statement.getQuery()).isEqualTo(expectedSql);
        verify(connection).prepareStatement(expectedSql);
    }

    // --- lineage

    @Test
    void shouldExposeLookupTableAsLineageSource() {
        ElasticsearchRowDataLookupFunction function =
                function(TABLE_NAME, List.of(REFERENCE_ID), List.of());

        LineageVertex vertex = function.getLineageVertex();

        assertThat(vertex.datasets()).hasSize(1);
        assertThat(vertex.datasets().get(0).name()).isEqualTo(TABLE_NAME);
    }

    // --- parameter binding

    @Test
    void shouldBindKeysBeforePushedDownParametersInOrder() throws Exception {
        ElasticsearchRowDataLookupFunction function =
                function(
                        TABLE_NAME,
                        List.of(REFERENCE_ID, VERSION),
                        List.of("\"order.details.attributes.productName\" = ?", "\"@version\" > ?"),
                        "Product X",
                        1);
        FieldNamedPreparedStatement statement = function.prepareStatement(connection);

        function.bindParameters(statement, GenericRowData.of(StringData.fromString("ref-1"), 7));

        InOrder order = inOrder(preparedStatement);
        order.verify(preparedStatement).clearParameters();
        order.verify(preparedStatement).setString(1, "ref-1");
        order.verify(preparedStatement).setInt(2, 7);
        order.verify(preparedStatement).setObject(3, "Product X");
        order.verify(preparedStatement).setObject(4, 1);
        verifyNoMoreInteractions(preparedStatement);
    }

    @Test
    void shouldBindPushedDownParametersFromFirstPositionWhenThereAreNoKeys() throws Exception {
        ElasticsearchRowDataLookupFunction function =
                function(TABLE_NAME, List.of(), List.of("\"@version\" > ?"), 1);
        FieldNamedPreparedStatement statement = function.prepareStatement(connection);

        function.bindParameters(statement, GenericRowData.of());

        verify(preparedStatement).setObject(1, 1);
    }

    @Test
    void shouldBindNullKeyAsSqlNull() throws Exception {
        ElasticsearchRowDataLookupFunction function =
                function(TABLE_NAME, List.of(REFERENCE_ID, VERSION), List.of());
        FieldNamedPreparedStatement statement = function.prepareStatement(connection);

        function.bindParameters(statement, GenericRowData.of(null, null));

        verify(preparedStatement).setNull(1, Types.VARCHAR);
        verify(preparedStatement).setNull(2, Types.INTEGER);
    }

    @Test
    void shouldBindTimestampKey() throws Exception {
        var types = List.of(DataTypes.STRING(), DataTypes.TIMESTAMP(3));
        ElasticsearchRowDataLookupFunction function =
                new ElasticsearchRowDataLookupFunction(
                        connectionOptions(TABLE_NAME),
                        0,
                        List.of(REFERENCE_ID, TIMESTAMP),
                        types,
                        List.of(TIMESTAMP),
                        lookupKeyConditions(List.of(TIMESTAMP)),
                        rowType(List.of(REFERENCE_ID, TIMESTAMP), types),
                        List.of(),
                        new Serializable[0]);
        FieldNamedPreparedStatement statement = function.prepareStatement(connection);
        LocalDateTime timestamp = LocalDateTime.of(2020, 1, 1, 10, 0);

        function.bindParameters(
                statement, GenericRowData.of(TimestampData.fromLocalDateTime(timestamp)));

        verify(preparedStatement).setTimestamp(1, Timestamp.valueOf(timestamp));
    }

    @Test
    void shouldClearParametersOnEveryBinding() throws Exception {
        ElasticsearchRowDataLookupFunction function =
                function(TABLE_NAME, List.of(REFERENCE_ID), List.of());
        FieldNamedPreparedStatement statement = function.prepareStatement(connection);

        function.bindParameters(statement, GenericRowData.of(StringData.fromString("a")));
        function.bindParameters(statement, GenericRowData.of(StringData.fromString("b")));

        verify(preparedStatement, times(2)).clearParameters();
        verify(preparedStatement).setString(1, "a");
        verify(preparedStatement).setString(1, "b");
    }

    @Test
    void shouldBindSpecialCharacterKeyAndPredicate() throws Exception {
        ElasticsearchRowDataLookupFunction function =
                specialCharacterFunction(TABLE_NAME, List.of("\"what?\" = ?"), "x");
        FieldNamedPreparedStatement statement = function.prepareStatement(connection);

        function.bindParameters(statement, GenericRowData.of(StringData.fromString("loc-1")));

        verify(preparedStatement).setString(1, "loc-1");
        verify(preparedStatement).setObject(2, "x");
    }

    @Test
    void shouldRejectKeyThatIsNotAField() {
        assertThatThrownBy(
                        () ->
                                new ElasticsearchRowDataLookupFunction(
                                        connectionOptions(TABLE_NAME),
                                        0,
                                        List.of(PRODUCT_NAME),
                                        List.of(DataTypes.STRING()),
                                        List.of(REFERENCE_ID),
                                        lookupKeyConditions(List.of(REFERENCE_ID)),
                                        RowType.of(DataTypes.STRING().getLogicalType()),
                                        List.of(),
                                        new Serializable[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(REFERENCE_ID);
    }

    @Test
    void shouldRejectLookupKeyConditionsNotMatchingKeys() {
        assertThatThrownBy(
                        () ->
                                new ElasticsearchRowDataLookupFunction(
                                        connectionOptions(TABLE_NAME),
                                        0,
                                        List.of(REFERENCE_ID),
                                        List.of(DataTypes.STRING()),
                                        List.of(REFERENCE_ID),
                                        List.of(),
                                        RowType.of(DataTypes.STRING().getLogicalType()),
                                        List.of(),
                                        new Serializable[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Expected a condition for every key.");
    }

    // --- upstream behaviour worked around by ElasticsearchRowDataLookupFunction

    @Test
    void upstreamQueryCannotBeParsedForDottedColumnNames() {
        // The upstream lookup query uses the column name as parameter name, which the parser
        // truncates at the first character that is not a Java identifier part.
        String upstreamQuery =
                dialect.getSelectFromStatement(
                        TABLE_NAME, new String[] {PRODUCT_NAME}, new String[] {REFERENCE_ID});
        Map<String, List<Integer>> parameters = new HashMap<>();

        String parsed =
                FieldNamedPreparedStatementImpl.parseNamedStatement(upstreamQuery, parameters);

        assertThat(parameters).containsOnlyKeys("order");
        assertThat(parsed).endsWith("= ?.details.referenceId");
    }

    @Test
    void upstreamParserTreatsColonInQuotedIdentifierAsParameter() {
        assertThatThrownBy(
                        () ->
                                FieldNamedPreparedStatement.prepareStatement(
                                        connection,
                                        "SELECT \"geo:loc\" FROM \"idx\" WHERE \"geo:loc\" = :k0",
                                        new String[] {"k0"},
                                        "",
                                        0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Expected 1 fields, but the parsing found 2");
    }

    @Test
    void upstreamParserRejectsQuestionMarkInQuotedIdentifier() {
        assertThatThrownBy(
                        () ->
                                FieldNamedPreparedStatement.prepareStatement(
                                        connection,
                                        "SELECT \"what?\" FROM \"idx\" WHERE \"what?\" = :k0",
                                        new String[] {"k0"},
                                        "",
                                        0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not contain ? character");
    }

    // --- helpers

    /** Function over (referenceId STRING, productName STRING, @version INT). */
    private ElasticsearchRowDataLookupFunction function(
            String tableName,
            List<String> keyNames,
            List<String> predicates,
            Serializable... pushdownParams) {
        var fieldNames = List.of(REFERENCE_ID, PRODUCT_NAME, VERSION);
        var types = List.of(DataTypes.STRING(), DataTypes.STRING(), DataTypes.INT());
        return new ElasticsearchRowDataLookupFunction(
                connectionOptions(tableName),
                0,
                fieldNames,
                types,
                keyNames,
                lookupKeyConditions(keyNames),
                rowType(fieldNames, types),
                predicates,
                pushdownParams);
    }

    /** Function over (geo:loc STRING, what? STRING) with key geo:loc. */
    private ElasticsearchRowDataLookupFunction specialCharacterFunction(
            String tableName, List<String> predicates, Serializable... pushdownParams) {
        var fieldNames = List.of(GEO, QUESTION);
        var types = List.of(DataTypes.STRING(), DataTypes.STRING());
        return new ElasticsearchRowDataLookupFunction(
                connectionOptions(tableName),
                0,
                fieldNames,
                types,
                List.of(GEO),
                lookupKeyConditions(List.of(GEO)),
                rowType(fieldNames, types),
                predicates,
                pushdownParams);
    }

    private List<String> lookupKeyConditions(List<String> keyNames) {
        return keyNames.stream().map(keyName -> dialect.quoteIdentifier(keyName) + " = ?").toList();
    }

    private static RowType rowType(List<String> fieldNames, List<DataType> types) {
        var fields = IntStream.range(0, fieldNames.size())
                .mapToObj(i -> DataTypes.FIELD(fieldNames.get(i), types.get(i)))
                .toArray(DataTypes.Field[]::new);
        return (RowType) DataTypes.ROW(fields).getLogicalType();
    }

    private InternalJdbcConnectionOptions connectionOptions(String tableName) {
        return InternalJdbcConnectionOptions.builder()
                .setDBUrl("jdbc:elasticsearch://localhost:9200")
                .setTableName(tableName)
                .setDialect(dialect)
                .build();
    }
}
