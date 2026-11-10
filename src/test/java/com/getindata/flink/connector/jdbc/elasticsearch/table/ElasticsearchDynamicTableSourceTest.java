package com.getindata.flink.connector.jdbc.elasticsearch.table;

import org.apache.flink.connector.jdbc.internal.options.JdbcReadOptions;
import org.apache.flink.connector.jdbc.statement.FieldNamedPreparedStatement;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.catalog.Column;
import org.apache.flink.table.catalog.ResolvedSchema;
import org.apache.flink.table.connector.source.abilities.SupportsFilterPushDown;
import org.apache.flink.table.connector.source.lookup.LookupOptions;
import org.apache.flink.table.connector.source.lookup.PartialCachingLookupProvider;
import org.apache.flink.table.connector.source.lookup.cache.DefaultLookupCache;
import org.apache.flink.table.connector.source.lookup.cache.LookupCache;
import org.apache.flink.table.data.GenericRowData;
import org.apache.flink.table.data.StringData;
import org.apache.flink.table.expressions.ResolvedExpression;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import javax.annotation.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.apache.flink.connector.jdbc.core.table.JdbcConnectorOptions.FILTER_HANDLING_POLICY;
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
                source.applyFilters(
                        List.of(SourceTableUtils.equalsFilter(PRODUCT_NAME, 1, "Product X")));

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
        ResolvedExpression filter = SourceTableUtils.upperEquals(PRODUCT_NAME, 1, "PRODUCT X");

        SupportsFilterPushDown.Result result = source.applyFilters(List.of(filter));

        assertThat(result.getAcceptedFilters()).isEmpty();
        assertThat(result.getRemainingFilters()).containsExactly(filter);
        assertThat(lookupSql(source)).isEqualTo(LOOKUP_QUERY);
    }

    @Test
    void shouldNotPushDownExpressionThatIsNotACall() throws Exception {
        ElasticsearchDynamicTableSource source = source();
        ResolvedExpression filter = SourceTableUtils.field(FLAG, DataTypes.BOOLEAN(), 4);

        SupportsFilterPushDown.Result result = source.applyFilters(List.of(filter));

        assertThat(result.getAcceptedFilters()).isEmpty();
        assertThat(result.getRemainingFilters()).containsExactly(filter);
        assertThat(lookupSql(source)).isEqualTo(LOOKUP_QUERY);
    }

    @Test
    void shouldQuoteColumnWithSpecialCharactersAndKeepValueAsParameter() throws Exception {
        ElasticsearchDynamicTableSource source = source();

        source.applyFilters(List.of(SourceTableUtils.equalsFilter(SPECIAL, 2, "a:b?")));

        assertThat(lookupSql(source)).isEqualTo(LOOKUP_QUERY + " AND ((\"what?:x\" = ?))");
        bindKey(source);
        verify(preparedStatement).setObject(2, "a:b?");
    }

    @Test
    void shouldEscapeDoubleQuoteInFilteredColumn() throws Exception {
        ElasticsearchDynamicTableSource source = source();

        source.applyFilters(List.of(SourceTableUtils.equalsFilter(QUOTED, 3, "x\"y")));

        assertThat(lookupSql(source)).isEqualTo(LOOKUP_QUERY + " AND ((\"a\"\"b\" = ?))");
        bindKey(source);
        verify(preparedStatement).setObject(2, "x\"y");
    }

    @Test
    void shouldKeepOrderOfPredicatesAndParametersForMultipleFilters() throws Exception {
        ElasticsearchDynamicTableSource source = source();

        source.applyFilters(
                List.of(
                        SourceTableUtils.equalsFilter(QUOTED, 3, "first"),
                        SourceTableUtils.equalsFilter(PRODUCT_NAME, 1, "second"),
                        SourceTableUtils.equalsFilter(SPECIAL, 2, "third")));

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
                List.of(
                        SourceTableUtils.equalsFilter(QUOTED, 3, "first"),
                        SourceTableUtils.equalsFilter(SPECIAL, 2, "second")));

        ElasticsearchDynamicTableSource copy = (ElasticsearchDynamicTableSource) source.copy();

        assertThat(lookupSql(copy)).isEqualTo(lookupSql(source));
    }

    private static final KeywordSubfields KEYWORD_SUBFIELDS =
            KeywordSubfields.of(Map.of(PRODUCT_NAME, PRODUCT_NAME + ".keyword"));
    private static final String PRODUCT_NAME_RECHECKED = """
            (("order.details.attributes.productName" = ?) \
            OR (("order.details.attributes.productName.keyword" IS NULL) \
            AND ("order.details.attributes.productName" IS NOT NULL)))""";

    @Test
    void recheckShouldReturnFiltersOnColumnsWithKeywordSubfieldAlsoAsRemaining() {
        var source = recheckSource();
        var onRecheckedColumn = SourceTableUtils.equalsFilter(PRODUCT_NAME, 1, "Product X");
        var onOtherColumn = SourceTableUtils.equalsFilter(SPECIAL, 2, "y");
        var unsupported = SourceTableUtils.upperEquals(PRODUCT_NAME, 1, "PRODUCT X");

        var result = source.applyFilters(List.of(onRecheckedColumn, onOtherColumn, unsupported));

        assertThat(result.getAcceptedFilters()).containsExactly(onRecheckedColumn, onOtherColumn);
        assertThat(result.getRemainingFilters()).containsExactly(unsupported, onRecheckedColumn);
        assertThat(result.getAcceptedFilters().get(0)).isSameAs(onRecheckedColumn);
        assertThat(result.getRemainingFilters().get(1)).isSameAs(onRecheckedColumn);
    }

    @Test
    void recheckShouldIncludeIgnoredKeywordsInScanQuery() {
        var source = recheckSource();

        source.applyFilters(
                List.of(
                        SourceTableUtils.equalsFilter(PRODUCT_NAME, 1, "Product X"),
                        SourceTableUtils.equalsFilter(SPECIAL, 2, "y")));

        assertThat(SourceTableUtils.scanSql(source))
                .endsWith(" FROM \"idx\" WHERE (%s) AND ((\"what?:x\" = ?))".formatted(PRODUCT_NAME_RECHECKED));
    }

    @Test
    void recheckShouldIncludeIgnoredKeywordsInPushedDownFilterOfLookup() throws Exception {
        var source = recheckSource();

        source.applyFilters(List.of(SourceTableUtils.equalsFilter(PRODUCT_NAME, 1, "Product X")));

        assertThat(lookupSql(source)).isEqualTo(LOOKUP_QUERY + " AND (%s)".formatted(PRODUCT_NAME_RECHECKED));
        bindKey(source);
        verify(preparedStatement).setObject(2, "Product X");
    }


    @Test
    void recheckShouldIncludeIgnoredKeywordsOnlyForLookupKeysWithKeywordSubfieldAndMatchThemExactly() {
        var source = recheckSource();

        var function = SourceTableUtils.runtimeLookupFunction(source, 0, 1);

        assertThat(function).isInstanceOf(ExactKeyMatchLookupFunction.class);
        var exactKeyMatch = (ExactKeyMatchLookupFunction<?>) function;
        assertThat(((ElasticsearchRowDataLookupFunction) exactKeyMatch.getDelegate()).getQuery())
                .endsWith("""
                        WHERE "order.details.referenceId" = ? \
                        AND ("order.details.attributes.productName" = ? \
                        OR (("order.details.attributes.productName.keyword" IS NULL) \
                        AND ("order.details.attributes.productName" IS NOT NULL)))""");
        var keyRow = GenericRowData.of(StringData.fromString("ref-1"), StringData.fromString("Product X"));
        assertThat(exactKeyMatch.getKeyMatches())
                .singleElement()
                .satisfies(key -> {
                    assertThat(key.matches(keyRow, row("other", "Product X"))).isTrue();
                    assertThat(key.matches(keyRow, row("ref-1", "Product Y"))).isFalse();
                });
    }


    @Test
    void recheckShouldMatchKeysExactlyInCachedLookupFunction() {
        var source = recheckSource(DefaultLookupCache.newBuilder().maximumSize(10).build());

        var provider =
                (PartialCachingLookupProvider) source.getLookupRuntimeProvider(SourceTableUtils.lookupContext(1));

        assertThat(provider.createLookupFunction()).isInstanceOf(ExactKeyMatchLookupFunction.class);
    }


    @Test
    void shouldQuoteLookupKeysWithSpecialCharacters() {
        var source = source();

        assertThat(SourceTableUtils.lookupFunction(source, 2).getQuery()).endsWith("WHERE \"what?:x\" = ?");
        assertThat(SourceTableUtils.lookupFunction(source, 3).getQuery()).endsWith("WHERE \"a\"\"b\" = ?");
    }

    @Test
    void copyShouldKeepKeywordSubfieldsAndQueries() throws Exception {
        var source = recheckSource();
        source.applyFilters(List.of(SourceTableUtils.equalsFilter(PRODUCT_NAME, 1, "Product X")));

        var copy = (ElasticsearchDynamicTableSource) source.copy();

        assertThat(copy.getKeywordSubfields()).isSameAs(source.getKeywordSubfields());
        assertThat(SourceTableUtils.scanSql(copy)).isEqualTo(SourceTableUtils.scanSql(source));
        assertThat(lookupSql(copy)).isEqualTo(lookupSql(source));
        assertThat(SourceTableUtils.lookupFunction(copy, 1).getQuery())
                .isEqualTo(SourceTableUtils.lookupFunction(source, 1).getQuery());
        assertThat(copy).isEqualTo(source);
    }

    @Test
    void disabledRecheckShouldNotChangeFiltersOrQueries() throws Exception {
        var source = source();
        var filter = SourceTableUtils.equalsFilter(PRODUCT_NAME, 1, "Product X");

        var result = source.applyFilters(List.of(filter));

        assertThat(result.getAcceptedFilters()).containsExactly(filter);
        assertThat(result.getRemainingFilters()).isEmpty();
        assertThat(SourceTableUtils.scanSql(source)).endsWith(" WHERE ((\"order.details.attributes.productName\" = ?))");
        assertThat(lookupSql(source)).isEqualTo(LOOKUP_QUERY + " AND ((\"order.details.attributes.productName\" = ?))");
    }

    @Test
    void shouldNotReadKeywordSubfieldsWhenFiltersAreNotPushedDown() {
        // recheck is enabled and reading from the unreachable Elasticsearch would fail
        var source = source(Map.of("filter.handling.policy", "never"));
        var filter = SourceTableUtils.equalsFilter(PRODUCT_NAME, 1, "Product X");

        var result = source.applyFilters(List.of(filter));

        assertThat(result.getAcceptedFilters()).isEmpty();
        assertThat(result.getRemainingFilters()).containsExactly(filter);
    }


    // --- helpers

    private String lookupSql(ElasticsearchDynamicTableSource source) throws Exception {
        Connection sqlConnection = mock(Connection.class);
        when(sqlConnection.prepareStatement(anyString())).thenReturn(mock(PreparedStatement.class));
        SourceTableUtils.lookupFunction(source, 0).prepareStatement(sqlConnection);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(sqlConnection).prepareStatement(sql.capture());
        return sql.getValue();
    }

    private void bindKey(ElasticsearchDynamicTableSource source) throws Exception {
        ElasticsearchRowDataLookupFunction function = SourceTableUtils.lookupFunction(source, 0);
        FieldNamedPreparedStatement statement = function.prepareStatement(connection);
        function.bindParameters(statement, GenericRowData.of(StringData.fromString("ref-1")));
    }

    private static GenericRowData row(String referenceId, String productName) {
        return GenericRowData.of(
                StringData.fromString(referenceId), StringData.fromString(productName), null, null, true);
    }

    private static ElasticsearchDynamicTableSource source() {
        return source(Map.of("filter.ignored-keyword.recheck", "false"));
    }

    private static ElasticsearchDynamicTableSource recheckSource() {
        return recheckSource(null);
    }

    private static ElasticsearchDynamicTableSource recheckSource(@Nullable LookupCache cache) {
        return new ElasticsearchDynamicTableSource(
                SourceTableUtils.unreachableOptions(),
                JdbcReadOptions.builder().build(),
                LookupOptions.MAX_RETRIES.defaultValue(),
                cache,
                FILTER_HANDLING_POLICY.defaultValue(),
                SCHEMA.toPhysicalRowDataType(),
                "default_catalog.default_database.t",
                KEYWORD_SUBFIELDS);
    }

    private static ElasticsearchDynamicTableSource source(Map<String, String> extraOptions) {
        Map<String, String> options = new HashMap<>();
        options.put("connector", ElasticsearchDynamicTableFactory.IDENTIFIER);
        options.put("url", "jdbc:elasticsearch://localhost:1");
        options.put("table-name", "idx");
        options.putAll(extraOptions);
        return SourceTableUtils.createSource(SCHEMA, options);
    }
}
