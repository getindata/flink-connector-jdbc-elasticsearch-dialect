package com.getindata.flink.connector.jdbc.elasticsearch.table;

import org.apache.flink.connector.jdbc.core.table.source.JdbcDynamicTableSource;
import org.apache.flink.connector.jdbc.statement.FieldNamedPreparedStatement;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.catalog.Column;
import org.apache.flink.table.catalog.ResolvedSchema;
import org.apache.flink.table.connector.source.DynamicTableSource;
import org.apache.flink.table.connector.source.LookupTableSource;
import org.apache.flink.table.connector.source.abilities.SupportsFilterPushDown;
import org.apache.flink.table.connector.source.lookup.LookupFunctionProvider;
import org.apache.flink.table.connector.source.lookup.PartialCachingLookupProvider;
import org.apache.flink.table.expressions.CallExpression;
import org.apache.flink.table.expressions.FieldReferenceExpression;
import org.apache.flink.table.expressions.ResolvedExpression;
import org.apache.flink.table.expressions.ValueLiteralExpression;
import org.apache.flink.table.factories.utils.FactoryMocks;
import org.apache.flink.table.functions.BuiltInFunctionDefinitions;
import org.apache.flink.table.runtime.connector.source.LookupRuntimeProviderContext;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ElasticsearchDynamicTableFactoryTest {

    private static final String REFERENCE_ID = "order.details.referenceId";
    private static final String PRODUCT_NAME = "order.details.attributes.productName";

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

        ElasticsearchRowDataLookupFunction function = lookupFunction(source, 0);

        assertThat(function.getQuery())
                .isEqualTo(
                        "SELECT \"order.details.referenceId\", "
                                + "\"order.details.attributes.productName\" "
                                + "FROM \"idx-orders.details-test\" "
                                + "WHERE \"order.details.referenceId\" = ?");
    }

    @Test
    void shouldUseProjectedSchemaForLookup() {
        ElasticsearchDynamicTableSource source = createSource(options());
        source.applyProjection(
                new int[][] {{1}}, DataTypes.ROW(DataTypes.FIELD(PRODUCT_NAME, DataTypes.STRING())));

        ElasticsearchRowDataLookupFunction function = lookupFunction(source, 0);

        assertThat(function.getQuery())
                .isEqualTo(
                        "SELECT \"order.details.attributes.productName\" "
                                + "FROM \"idx-orders.details-test\" "
                                + "WHERE \"order.details.attributes.productName\" = ?");
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
                source.getLookupRuntimeProvider(lookupContext(0));

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
                source.getLookupRuntimeProvider(lookupContext(0));

        assertThat(provider).isInstanceOf(PartialCachingLookupProvider.class);
        assertThat(((PartialCachingLookupProvider) provider).createLookupFunction())
                .isInstanceOf(ElasticsearchRowDataLookupFunction.class);
    }

    @Test
    void shouldNotCreateCacheByDefault() {
        LookupTableSource.LookupRuntimeProvider provider =
                createSource(options()).getLookupRuntimeProvider(lookupContext(0));

        assertThat(provider).isNotInstanceOf(PartialCachingLookupProvider.class);
    }

    @Test
    void shouldPushDownFiltersLikeJdbcSource() {
        ElasticsearchDynamicTableSource source = createSource(options());
        JdbcDynamicTableSource jdbcSource =
                (JdbcDynamicTableSource)
                        FactoryMocks.createTableSource(SCHEMA, jdbcOptions());
        List<ResolvedExpression> filters = Collections.singletonList(productNameEquals("Product X"));

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
        source.applyFilters(Arrays.asList(productNameEquals("Product X"), productNameEquals("Product Y")));
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
        ElasticsearchDynamicTableSource source =
                (ElasticsearchDynamicTableSource) FactoryMocks.createTableSource(schema, options());

        ElasticsearchRowDataLookupFunction function = lookupFunction(source, 1);

        assertThat(function.getQuery())
                .isEqualTo(
                        "SELECT \"order.details.referenceId\", \"geo:loc\" "
                                + "FROM \"idx-orders.details-test\" "
                                + "WHERE \"geo:loc\" = ?");
    }

    @Test
    void shouldCreateLookupWithPushedDownFilterOnProjectedOutColumnWithQuestionMark()
            throws Exception {
        ResolvedSchema schema =
                ResolvedSchema.of(
                        Column.physical(REFERENCE_ID, DataTypes.STRING()),
                        Column.physical("what?", DataTypes.STRING()));
        ElasticsearchDynamicTableSource source =
                (ElasticsearchDynamicTableSource) FactoryMocks.createTableSource(schema, options());
        source.applyFilters(
                Collections.singletonList(
                        CallExpression.permanent(
                                BuiltInFunctionDefinitions.EQUALS,
                                Arrays.asList(
                                        new FieldReferenceExpression(
                                                "what?", DataTypes.STRING(), 0, 1),
                                        new ValueLiteralExpression("x")),
                                DataTypes.BOOLEAN())));
        source.applyProjection(
                new int[][] {{0}}, DataTypes.ROW(DataTypes.FIELD(REFERENCE_ID, DataTypes.STRING())));
        Connection connection = mock(Connection.class);
        PreparedStatement preparedStatement = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);

        FieldNamedPreparedStatement statement =
                lookupFunction(source, 0).prepareStatement(connection);

        assertThat(statement).isInstanceOf(PositionalPreparedStatement.class);
        verify(connection)
                .prepareStatement(
                        "SELECT \"order.details.referenceId\" "
                                + "FROM \"idx-orders.details-test\" "
                                + "WHERE \"order.details.referenceId\" = ? "
                                // the visitor and the lookup function both add parentheses
                                + "AND ((\"what?\" = ?))");
    }

    @Test
    void shouldCreateLookupOnCrossClusterTable() {
        Map<String, String> options = options();
        options.put("table-name", "remote:idx-orders");

        ElasticsearchRowDataLookupFunction function = lookupFunction(createSource(options), 0);

        assertThat(function.getQuery())
                .isEqualTo(
                        "SELECT \"order.details.referenceId\", "
                                + "\"order.details.attributes.productName\" "
                                + "FROM \"remote:idx-orders\" "
                                + "WHERE \"order.details.referenceId\" = ?");
    }

    @Test
    void shouldCreateLookupWithMultipleKeysInLookupContextOrder() {
        LookupTableSource.LookupRuntimeProvider provider =
                createSource(options())
                        .getLookupRuntimeProvider(
                                new LookupRuntimeProviderContext(new int[][] {{1}, {0}}, false));

        ElasticsearchRowDataLookupFunction function =
                (ElasticsearchRowDataLookupFunction)
                        ((LookupFunctionProvider) provider).createLookupFunction();

        assertThat(function.getQuery())
                .endsWith(
                        "WHERE \"order.details.attributes.productName\" = ? "
                                + "AND \"order.details.referenceId\" = ?");
    }

    private static ElasticsearchRowDataLookupFunction lookupFunction(
            ElasticsearchDynamicTableSource source, int keyIndex) {
        LookupTableSource.LookupRuntimeProvider provider =
                source.getLookupRuntimeProvider(lookupContext(keyIndex));
        assertThat(provider).isInstanceOf(LookupFunctionProvider.class);
        return (ElasticsearchRowDataLookupFunction)
                ((LookupFunctionProvider) provider).createLookupFunction();
    }

    private static LookupRuntimeProviderContext lookupContext(int keyIndex) {
        return new LookupRuntimeProviderContext(new int[][] {{keyIndex}}, false);
    }

    private static ResolvedExpression productNameEquals(String value) {
        return CallExpression.permanent(
                BuiltInFunctionDefinitions.EQUALS,
                Arrays.asList(
                        new FieldReferenceExpression(PRODUCT_NAME, DataTypes.STRING(), 0, 1),
                        new ValueLiteralExpression(value)),
                DataTypes.BOOLEAN());
    }

    private static ElasticsearchDynamicTableSource createSource(Map<String, String> options) {
        return (ElasticsearchDynamicTableSource) FactoryMocks.createTableSource(SCHEMA, options);
    }

    private static Map<String, String> options() {
        Map<String, String> options = new HashMap<>();
        options.put("connector", ElasticsearchDynamicTableFactory.IDENTIFIER);
        options.put("url", "jdbc:elasticsearch://localhost:9200");
        options.put("table-name", "idx-orders.details-test");
        return options;
    }

    private static Map<String, String> jdbcOptions() {
        Map<String, String> options = options();
        options.put("connector", "jdbc");
        return options;
    }
}
