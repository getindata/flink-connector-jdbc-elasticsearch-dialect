package com.getindata.flink.connector.jdbc.elasticsearch.table;

import org.apache.flink.configuration.Configuration;
import org.apache.flink.connector.jdbc.core.table.source.JdbcDynamicTableSource;
import org.apache.flink.connector.jdbc.statement.FieldNamedPreparedStatement;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.api.ValidationException;
import org.apache.flink.table.catalog.Column;
import org.apache.flink.table.catalog.ResolvedSchema;
import org.apache.flink.table.connector.source.DynamicTableSource;
import org.apache.flink.table.connector.source.LookupTableSource;
import org.apache.flink.table.connector.source.abilities.SupportsFilterPushDown;
import org.apache.flink.table.connector.source.lookup.PartialCachingLookupProvider;
import org.apache.flink.table.expressions.ResolvedExpression;
import org.apache.flink.table.factories.utils.FactoryMocks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ElasticsearchDynamicTableFactoryTest {

    private static final String REFERENCE_ID = "order.details.referenceId";
    private static final String PRODUCT_NAME = "order.details.attributes.productName";

    private static final String HANDLING = "filter.ignored-keyword.handling";
    private static final String URL = "jdbc:elasticsearch://localhost:9200";

    private static final ResolvedSchema SCHEMA =
            ResolvedSchema.of(
                    Column.physical(REFERENCE_ID, DataTypes.STRING()),
                    Column.physical(PRODUCT_NAME, DataTypes.STRING()));

    @Test
    void shouldCreateElasticsearchSource() {
        DynamicTableSource source = FactoryMocks.createTableSource(SCHEMA, options());

        assertThat(source).isInstanceOf(ElasticsearchDynamicTableSource.class);
    }

    @Test
    void shouldCreateLookupFunctionWithPositionalParameters() {
        ElasticsearchDynamicTableSource source = createSource(options());

        ElasticsearchRowDataLookupFunction function = SourceTableUtils.lookupFunction(source, 0);

        assertThat(function.getQuery())
                .isEqualTo("""
                        SELECT "order.details.referenceId", "order.details.attributes.productName" \
                        FROM "idx-orders.details-test" \
                        WHERE "order.details.referenceId" = ?""");
    }

    @Test
    void shouldUseProjectedSchemaForLookup() {
        ElasticsearchDynamicTableSource source = createSource(options());
        source.applyProjection(
                new int[][] {{1}}, DataTypes.ROW(DataTypes.FIELD(PRODUCT_NAME, DataTypes.STRING())));

        ElasticsearchRowDataLookupFunction function = SourceTableUtils.lookupFunction(source, 0);

        assertThat(function.getQuery())
                .isEqualTo("""
                        SELECT "order.details.attributes.productName" \
                        FROM "idx-orders.details-test" \
                        WHERE "order.details.attributes.productName" = ?""");
    }

    @Test
    void shouldUseCachingProviderWhenCacheIsConfigured() {
        Map<String, String> options = options();
        options.put("lookup.cache", "PARTIAL");
        options.put("lookup.partial-cache.max-rows", "100");
        options.put("lookup.partial-cache.expire-after-write", "10min");
        options.put("lookup.partial-cache.cache-missing-key", "false");
        ElasticsearchDynamicTableSource source = createSource(options);

        LookupTableSource.LookupRuntimeProvider provider =
                source.getLookupRuntimeProvider(SourceTableUtils.lookupContext(0));

        assertThat(provider).isInstanceOf(PartialCachingLookupProvider.class);
        assertThat(((PartialCachingLookupProvider) provider).createLookupFunction())
                .isInstanceOf(ElasticsearchRowDataLookupFunction.class);
    }

    @Test
    void shouldUseCachingProviderWhenLegacyCacheOptionsAreConfigured() {
        Map<String, String> options = options();
        options.put("lookup.cache.max-rows", "100");
        options.put("lookup.cache.ttl", "10min");
        ElasticsearchDynamicTableSource source = createSource(options);

        LookupTableSource.LookupRuntimeProvider provider =
                source.getLookupRuntimeProvider(SourceTableUtils.lookupContext(0));

        assertThat(provider).isInstanceOf(PartialCachingLookupProvider.class);
        assertThat(((PartialCachingLookupProvider) provider).createLookupFunction())
                .isInstanceOf(ElasticsearchRowDataLookupFunction.class);
    }

    @Test
    void shouldNotCreateCacheByDefault() {
        LookupTableSource.LookupRuntimeProvider provider =
                createSource(options()).getLookupRuntimeProvider(SourceTableUtils.lookupContext(0));

        assertThat(provider).isNotInstanceOf(PartialCachingLookupProvider.class);
    }

    @Test
    void shouldPushDownFiltersLikeJdbcSource() {
        ElasticsearchDynamicTableSource source = createSource(options());
        JdbcDynamicTableSource jdbcSource =
                (JdbcDynamicTableSource)
                        FactoryMocks.createTableSource(SCHEMA, jdbcOptions());
        List<ResolvedExpression> filters = List.of(productNameEquals("Product X"));

        SupportsFilterPushDown.Result result = source.applyFilters(filters);
        SupportsFilterPushDown.Result jdbcResult = jdbcSource.applyFilters(filters);

        assertThat(result.getAcceptedFilters()).isEqualTo(jdbcResult.getAcceptedFilters());
        assertThat(result.getRemainingFilters()).isEqualTo(jdbcResult.getRemainingFilters());
    }

    @Test
    void copyShouldPreservePushedDownState() {
        ElasticsearchDynamicTableSource source = createSource(options());
        source.applyProjection(
                new int[][] {{0}, {1}},
                DataTypes.ROW(
                        DataTypes.FIELD(REFERENCE_ID, DataTypes.STRING()),
                        DataTypes.FIELD(PRODUCT_NAME, DataTypes.STRING())));
        source.applyFilters(List.of(productNameEquals("Product X"), productNameEquals("Product Y")));
        source.applyLimit(10);

        DynamicTableSource copy = source.copy();

        assertThat(copy).isInstanceOf(ElasticsearchDynamicTableSource.class);
        // JdbcDynamicTableSource#equals compares predicates, parameters and limit of the parent.
        assertThat(copy).isEqualTo(source);
        assertThat(copy.hashCode()).isEqualTo(source.hashCode());
        assertThat(copy).isNotEqualTo(createSource(options()));
    }

    @Test
    void shouldNotBeEqualToPlainJdbcSource() {
        ElasticsearchDynamicTableSource source = createSource(options());
        JdbcDynamicTableSource jdbcSource =
                (JdbcDynamicTableSource) FactoryMocks.createTableSource(SCHEMA, jdbcOptions());

        assertThat(source).isNotEqualTo(jdbcSource);
    }

    @Test
    void shouldCreateLookupOnColumnWithColon() {
        ResolvedSchema schema =
                ResolvedSchema.of(
                        Column.physical(REFERENCE_ID, DataTypes.STRING()),
                        Column.physical("geo:loc", DataTypes.STRING()));
        ElasticsearchDynamicTableSource source = SourceTableUtils.createSource(schema, options());

        ElasticsearchRowDataLookupFunction function = SourceTableUtils.lookupFunction(source, 1);

        assertThat(function.getQuery())
                .isEqualTo("""
                        SELECT "order.details.referenceId", "geo:loc" \
                        FROM "idx-orders.details-test" \
                        WHERE "geo:loc" = ?""");
    }

    @Test
    void shouldCreateLookupWithPushedDownFilterOnProjectedOutColumnWithQuestionMark()
            throws Exception {
        ResolvedSchema schema =
                ResolvedSchema.of(
                        Column.physical(REFERENCE_ID, DataTypes.STRING()),
                        Column.physical("what?", DataTypes.STRING()));
        ElasticsearchDynamicTableSource source = SourceTableUtils.createSource(schema, options());
        source.applyFilters(List.of(SourceTableUtils.equalsFilter("what?", 1, "x")));
        source.applyProjection(
                new int[][] {{0}}, DataTypes.ROW(DataTypes.FIELD(REFERENCE_ID, DataTypes.STRING())));
        Connection connection = mock(Connection.class);
        PreparedStatement preparedStatement = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);

        FieldNamedPreparedStatement statement =
                SourceTableUtils.lookupFunction(source, 0).prepareStatement(connection);

        assertThat(statement).isInstanceOf(PositionalPreparedStatement.class);
        // the visitor and the lookup function both add parentheses
        verify(connection)
                .prepareStatement("""
                        SELECT "order.details.referenceId" \
                        FROM "idx-orders.details-test" \
                        WHERE "order.details.referenceId" = ? \
                        AND (("what?" = ?))""");
    }

    @Test
    void shouldCreateLookupOnCrossClusterTable() {
        Map<String, String> options = options();
        options.put("table-name", "remote:idx-orders");

        ElasticsearchRowDataLookupFunction function =
                SourceTableUtils.lookupFunction(createSource(options), 0);

        assertThat(function.getQuery())
                .isEqualTo("""
                        SELECT "order.details.referenceId", "order.details.attributes.productName" \
                        FROM "remote:idx-orders" \
                        WHERE "order.details.referenceId" = ?""");
    }

    @Test
    void shouldCreateLookupWithMultipleKeysInLookupContextOrder() {
        ElasticsearchRowDataLookupFunction function =
                (ElasticsearchRowDataLookupFunction)
                        SourceTableUtils.runtimeLookupFunction(createSource(options()), 1, 0);

        assertThat(function.getQuery())
                .endsWith("""
                        WHERE "order.details.attributes.productName" = ? \
                        AND "order.details.referenceId" = ?""");
    }

    @Test
    void recheckShouldBeEnabledByDefault() {
        assertThat(handlingSource(Map.of(), new Configuration()).getKeywordSubfields())
                .isNotSameAs(KeywordSubfields.NONE);
    }

    @Test
    void handlingShouldBeTakenFromSession() {
        assertThat(handlingSource(Map.of(), session("none")).getKeywordSubfields()).isSameAs(KeywordSubfields.NONE);
        assertThat(handlingSource(Map.of(), session("recheck")).getKeywordSubfields())
                .isNotSameAs(KeywordSubfields.NONE);
    }

    @Test
    void tableOptionShouldOverrideSession() {
        assertThat(handlingSource(Map.of(HANDLING, "recheck"), session("none")).getKeywordSubfields())
                .isNotSameAs(KeywordSubfields.NONE);
        assertThat(handlingSource(Map.of(HANDLING, "none"), session("recheck")).getKeywordSubfields())
                .isSameAs(KeywordSubfields.NONE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"NONE", "None", "none"})
    void handlingShouldBeCaseInsensitive(String value) {
        assertThat(handlingSource(Map.of(HANDLING, value), new Configuration()).getKeywordSubfields())
                .isSameAs(KeywordSubfields.NONE);
    }

    @Test
    void shouldRejectInvalidHandlingValue() {
        assertThatThrownBy(() -> handlingSource(Map.of(HANDLING, "true"), new Configuration()))
                .isInstanceOf(ValidationException.class)
                .hasStackTraceContaining(HANDLING);
    }

    @Test
    void shouldAcceptHandlingAsTableOption() {
        assertThat(new ElasticsearchDynamicTableFactory().optionalOptions())
                .contains(EsConnectorOptions.FILTER_IGNORED_KEYWORD_HANDLING);
    }

    @Test
    void recheckShouldMakeMultiValueHandlingStrict() {
        var strictUrl = URL + "/?field.multi.value.leniency=false";

        assertThat(handlingSource(Map.of(), new Configuration()).getConnectionOptions())
                .satisfies(options -> assertThat(options.getDbURL()).isEqualTo(URL))
                .satisfies(options -> assertThat(options.getProperties())
                        .containsEntry("field.multi.value.leniency", "false"));
        assertThat(handlingSource(Map.of("url", strictUrl), new Configuration()).getConnectionOptions())
                .satisfies(options -> assertThat(options.getDbURL()).isEqualTo(strictUrl))
                .satisfies(options -> assertThat(options.getProperties())
                        .containsEntry("field.multi.value.leniency", "false"));
    }

    @Test
    void noneShouldKeepMultiValueHandling() {
        var lenientUrl = URL + "/?field.multi.value.leniency=true";

        assertThat(handlingSource(Map.of(HANDLING, "none", "url", lenientUrl), new Configuration())
                .getConnectionOptions())
                .satisfies(options -> assertThat(options.getDbURL()).isEqualTo(lenientUrl))
                .satisfies(options -> assertThat(options.getProperties())
                        .doesNotContainKey("field.multi.value.leniency"));
    }

    @Test
    void recheckShouldRejectLenientMultiValueHandling() {
        assertThatThrownBy(() -> handlingSource(
                        Map.of("url", URL + "/?field.multi.value.leniency=true"), new Configuration()))
                .isInstanceOf(ValidationException.class)
                .hasStackTraceContaining("'field.multi.value.leniency' = 'true' cannot be used with")
                .hasStackTraceContaining("'filter.ignored-keyword.handling' = 'none'");
        assertThatThrownBy(() -> handlingSource(
                        Map.of("connection-properties.field.multi.value.leniency", "true"), session("recheck")))
                .isInstanceOf(ValidationException.class);
    }

    private static Configuration session(String handling) {
        var session = new Configuration();
        session.setString("elasticsearch-jdbc." + HANDLING, handling);
        return session;
    }

    private static ElasticsearchDynamicTableSource handlingSource(
            Map<String, String> tableOptions, Configuration session) {
        var options = options();
        options.remove(HANDLING);
        options.putAll(tableOptions);
        return (ElasticsearchDynamicTableSource) FactoryMocks.createTableSource(SCHEMA, options, session);
    }

    private static ResolvedExpression productNameEquals(String value) {
        return SourceTableUtils.equalsFilter(PRODUCT_NAME, 1, value);
    }

    private static ElasticsearchDynamicTableSource createSource(Map<String, String> options) {
        return SourceTableUtils.createSource(SCHEMA, options);
    }

    private static Map<String, String> options() {
        Map<String, String> options = new HashMap<>();
        options.put("connector", ElasticsearchDynamicTableFactory.IDENTIFIER);
        options.put("url", URL);
        options.put("table-name", "idx-orders.details-test");
        options.put(HANDLING, "none");
        return options;
    }

    private static Map<String, String> jdbcOptions() {
        Map<String, String> options = options();
        options.put("connector", "jdbc");
        options.remove(HANDLING);
        return options;
    }
}
