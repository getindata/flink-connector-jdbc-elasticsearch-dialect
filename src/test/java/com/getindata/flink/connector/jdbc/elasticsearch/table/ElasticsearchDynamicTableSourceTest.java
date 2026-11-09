package com.getindata.flink.connector.jdbc.elasticsearch.table;

import org.apache.flink.connector.jdbc.statement.FieldNamedPreparedStatement;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.catalog.Column;
import org.apache.flink.table.catalog.ResolvedSchema;
import org.apache.flink.table.connector.source.abilities.SupportsFilterPushDown;
import org.apache.flink.table.connector.source.lookup.LookupFunctionProvider;
import org.apache.flink.table.data.GenericRowData;
import org.apache.flink.table.data.StringData;
import org.apache.flink.table.expressions.CallExpression;
import org.apache.flink.table.expressions.FieldReferenceExpression;
import org.apache.flink.table.expressions.ResolvedExpression;
import org.apache.flink.table.expressions.ValueLiteralExpression;
import org.apache.flink.table.factories.utils.FactoryMocks;
import org.apache.flink.table.functions.BuiltInFunctionDefinitions;
import org.apache.flink.table.runtime.connector.source.LookupRuntimeProviderContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Filter push down of {@link ElasticsearchDynamicTableSource} as seen by the lookup function. */
class ElasticsearchDynamicTableSourceTest {

    private static final String REFERENCE_ID = "order.details.referenceId";
    private static final String PRODUCT_NAME = "order.details.attributes.productName";
    private static final String SPECIAL = "what?:x";
    private static final String QUOTED = "a\"b";
    private static final String FLAG = "flag";

    private static final ResolvedSchema SCHEMA =
            ResolvedSchema.of(
                    Column.physical(REFERENCE_ID, DataTypes.STRING()),
                    Column.physical(PRODUCT_NAME, DataTypes.STRING()),
                    Column.physical(SPECIAL, DataTypes.STRING()),
                    Column.physical(QUOTED, DataTypes.STRING()),
                    Column.physical(FLAG, DataTypes.BOOLEAN()));

    private static final String LOOKUP_QUERY = """
            SELECT "order.details.referenceId", "order.details.attributes.productName", \
            "what?:x", "a""b", "flag" \
            FROM "idx" WHERE "order.details.referenceId" = ?""";

    private Connection connection;
    private PreparedStatement preparedStatement;

    @BeforeEach
    void setUp() throws Exception {
        connection = mock(Connection.class);
        preparedStatement = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
    }

    @Test
    void shouldPushDownSupportedFilterToLookup() throws Exception {
        ElasticsearchDynamicTableSource source = source();

        SupportsFilterPushDown.Result result =
                source.applyFilters(List.of(equalsFilter(PRODUCT_NAME, 1, "Product X")));

        assertThat(result.getAcceptedFilters()).hasSize(1);
        assertThat(result.getRemainingFilters()).isEmpty();
        assertThat(lookupSql(source))
                .isEqualTo(LOOKUP_QUERY + " AND ((\"order.details.attributes.productName\" = ?))");
        bindKey(source);
        InOrder order = inOrder(preparedStatement);
        order.verify(preparedStatement).setString(1, "ref-1");
        order.verify(preparedStatement).setObject(2, "Product X");
    }

    @Test
    void shouldNotPushDownUnsupportedFunction() throws Exception {
        ElasticsearchDynamicTableSource source = source();
        ResolvedExpression filter =
                CallExpression.permanent(
                        BuiltInFunctionDefinitions.EQUALS,
                        List.of(
                                CallExpression.permanent(
                                        BuiltInFunctionDefinitions.UPPER,
                                        List.of(field(PRODUCT_NAME, 1)),
                                        DataTypes.STRING()),
                                new ValueLiteralExpression("PRODUCT X")),
                        DataTypes.BOOLEAN());

        SupportsFilterPushDown.Result result = source.applyFilters(List.of(filter));

        assertThat(result.getAcceptedFilters()).isEmpty();
        assertThat(result.getRemainingFilters()).containsExactly(filter);
        assertThat(lookupSql(source)).isEqualTo(LOOKUP_QUERY);
    }

    @Test
    void shouldNotPushDownExpressionThatIsNotACall() throws Exception {
        ElasticsearchDynamicTableSource source = source();
        ResolvedExpression filter = new FieldReferenceExpression(FLAG, DataTypes.BOOLEAN(), 0, 4);

        SupportsFilterPushDown.Result result = source.applyFilters(List.of(filter));

        assertThat(result.getAcceptedFilters()).isEmpty();
        assertThat(result.getRemainingFilters()).containsExactly(filter);
        assertThat(lookupSql(source)).isEqualTo(LOOKUP_QUERY);
    }

    @Test
    void shouldQuoteColumnWithSpecialCharactersAndKeepValueAsParameter() throws Exception {
        ElasticsearchDynamicTableSource source = source();

        source.applyFilters(List.of(equalsFilter(SPECIAL, 2, "a:b?")));

        assertThat(lookupSql(source)).isEqualTo(LOOKUP_QUERY + " AND ((\"what?:x\" = ?))");
        bindKey(source);
        verify(preparedStatement).setObject(2, "a:b?");
    }

    @Test
    void shouldEscapeDoubleQuoteInFilteredColumn() throws Exception {
        ElasticsearchDynamicTableSource source = source();

        source.applyFilters(List.of(equalsFilter(QUOTED, 3, "x\"y")));

        assertThat(lookupSql(source)).isEqualTo(LOOKUP_QUERY + " AND ((\"a\"\"b\" = ?))");
        bindKey(source);
        verify(preparedStatement).setObject(2, "x\"y");
    }

    @Test
    void shouldKeepOrderOfPredicatesAndParametersForMultipleFilters() throws Exception {
        ElasticsearchDynamicTableSource source = source();

        source.applyFilters(
                List.of(
                        equalsFilter(QUOTED, 3, "first"),
                        equalsFilter(PRODUCT_NAME, 1, "second"),
                        equalsFilter(SPECIAL, 2, "third")));

        assertThat(lookupSql(source))
                .isEqualTo("""
                        %s \
                        AND (("a""b" = ?)) \
                        AND (("order.details.attributes.productName" = ?)) \
                        AND (("what?:x" = ?))""".formatted(LOOKUP_QUERY));
        bindKey(source);
        InOrder order = inOrder(preparedStatement);
        order.verify(preparedStatement).setString(1, "ref-1");
        order.verify(preparedStatement).setObject(2, "first");
        order.verify(preparedStatement).setObject(3, "second");
        order.verify(preparedStatement).setObject(4, "third");
    }

    @Test
    void copyShouldKeepPushedDownPredicatesAndParameters() throws Exception {
        ElasticsearchDynamicTableSource source = source();
        source.applyFilters(
                List.of(equalsFilter(QUOTED, 3, "first"), equalsFilter(SPECIAL, 2, "second")));

        ElasticsearchDynamicTableSource copy = (ElasticsearchDynamicTableSource) source.copy();

        assertThat(lookupSql(copy)).isEqualTo(lookupSql(source));
    }

    // --- helpers

    private String lookupSql(ElasticsearchDynamicTableSource source) throws Exception {
        Connection sqlConnection = mock(Connection.class);
        when(sqlConnection.prepareStatement(anyString())).thenReturn(mock(PreparedStatement.class));
        lookupFunction(source).prepareStatement(sqlConnection);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(sqlConnection).prepareStatement(sql.capture());
        return sql.getValue();
    }

    private void bindKey(ElasticsearchDynamicTableSource source) throws Exception {
        ElasticsearchRowDataLookupFunction function = lookupFunction(source);
        FieldNamedPreparedStatement statement = function.prepareStatement(connection);
        function.bindParameters(statement, GenericRowData.of(StringData.fromString("ref-1")));
    }

    private static ElasticsearchRowDataLookupFunction lookupFunction(
            ElasticsearchDynamicTableSource source) {
        LookupFunctionProvider provider =
                (LookupFunctionProvider)
                        source.getLookupRuntimeProvider(
                                new LookupRuntimeProviderContext(new int[][] {{0}}, false));
        return (ElasticsearchRowDataLookupFunction) provider.createLookupFunction();
    }

    private static ResolvedExpression equalsFilter(String column, int index, String value) {
        return CallExpression.permanent(
                BuiltInFunctionDefinitions.EQUALS,
                List.of(field(column, index), new ValueLiteralExpression(value)),
                DataTypes.BOOLEAN());
    }

    private static FieldReferenceExpression field(String column, int index) {
        return new FieldReferenceExpression(column, DataTypes.STRING(), 0, index);
    }

    private static ElasticsearchDynamicTableSource source() {
        Map<String, String> options = new HashMap<>();
        options.put("connector", ElasticsearchDynamicTableFactory.IDENTIFIER);
        options.put("url", "jdbc:elasticsearch://localhost:9200");
        options.put("table-name", "idx");
        return (ElasticsearchDynamicTableSource) FactoryMocks.createTableSource(SCHEMA, options);
    }
}
