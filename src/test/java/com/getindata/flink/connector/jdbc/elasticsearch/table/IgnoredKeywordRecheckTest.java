package com.getindata.flink.connector.jdbc.elasticsearch.table;

import com.getindata.flink.connector.jdbc.elasticsearch.database.dialect.ElasticsearchDialect;

import org.apache.flink.connector.jdbc.core.table.FilterHandlingPolicy;
import org.apache.flink.connector.jdbc.internal.options.InternalJdbcConnectionOptions;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.api.ValidationException;
import org.apache.flink.table.connector.source.abilities.SupportsFilterPushDown.Result;
import org.apache.flink.table.functions.BuiltInFunctionDefinitions;
import org.apache.flink.table.types.DataType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IgnoredKeywordRecheckTest {

    private static final String REFERENCE_ID = "referenceId";
    private static final String NAME = "name";
    private static final String FLAG = "flag";

    private static final DataType PHYSICAL_ROW =
            DataTypes.ROW(
                    DataTypes.FIELD(REFERENCE_ID, DataTypes.STRING()),
                    DataTypes.FIELD(NAME, DataTypes.STRING()),
                    DataTypes.FIELD(FLAG, DataTypes.BOOLEAN()));

    private static final ElasticsearchDialect DIALECT = new ElasticsearchDialect();

    private static final String NAME_KEY_CONDITION = """
            ("name" = ? OR (("name.keyword" IS NULL) AND ("name" IS NOT NULL)))""";

    @Test
    void shouldExtendOnlyFiltersOnRecheckColumns() {
        var recheck = recheckWithColumns(FilterHandlingPolicy.ALWAYS);
        var onRecheckColumn = SourceTableUtils.equalsFilter(NAME, 1, "x");
        var onOtherColumn = SourceTableUtils.equalsFilter(REFERENCE_ID, 0, "y");

        var extendedFilters = recheck.extendFilters(List.of(onRecheckColumn, onOtherColumn));

        assertThat(extendedFilters.filters()).hasSize(2);
        assertThat(extendedFilters.filters().get(0)).isNotSameAs(onRecheckColumn);
        assertThat(extendedFilters.filters().get(1)).isSameAs(onOtherColumn);
        assertThat(extendedFilters.originalOf(extendedFilters.filters().get(0))).isSameAs(onRecheckColumn);
    }

    @Test
    void shouldReturnOriginalFiltersAndRecheckedAcceptedAlsoAsRemaining() {
        var recheck = recheckWithColumns(FilterHandlingPolicy.ALWAYS);
        var onRecheckColumn = SourceTableUtils.equalsFilter(NAME, 1, "x");
        var onOtherColumn = SourceTableUtils.equalsFilter(REFERENCE_ID, 0, "y");
        var rejected = SourceTableUtils.equalsFilter(REFERENCE_ID, 0, "z");
        var extendedFilters = recheck.extendFilters(List.of(onRecheckColumn, onOtherColumn, rejected));
        var extended = extendedFilters.filters();

        var result = extendedFilters.toPlannerResult(Result.of(List.of(extended.get(0), extended.get(1)), List.of(extended.get(2))));

        assertThat(result.getAcceptedFilters()).containsExactly(onRecheckColumn, onOtherColumn);
        assertThat(result.getRemainingFilters()).containsExactly(rejected, onRecheckColumn);
        assertThat(result.getAcceptedFilters().get(0)).isSameAs(onRecheckColumn);
        assertThat(result.getRemainingFilters().get(1)).isSameAs(onRecheckColumn);
    }

    @Test
    void shouldNotExtendNorReadMetadataWhenDisabled() {
        // no columns set and no Elasticsearch: reading the metadata would fail
        var recheck = new IgnoredKeywordRecheck(false, FilterHandlingPolicy.ALWAYS, unreachableOptions());
        var filter = SourceTableUtils.equalsFilter(NAME, 1, "x");

        var extendedFilters = recheck.extendFilters(List.of(filter));
        var lookupKeys = recheck.lookupKeys(new int[][] {{1}}, PHYSICAL_ROW);

        assertThat(extendedFilters.filters()).singleElement().isSameAs(filter);
        assertThat(lookupKeys.conditions(DIALECT)).containsExactly("\"name\" = ?");
    }

    @Test
    void shouldNotExtendFiltersNorReadMetadataWhenFiltersAreNotPushedDown() {
        var recheck = new IgnoredKeywordRecheck(true, FilterHandlingPolicy.NEVER, unreachableOptions());
        var filter = SourceTableUtils.equalsFilter(NAME, 1, "x");

        var extendedFilters = recheck.extendFilters(List.of(filter));

        assertThat(extendedFilters.filters()).singleElement().isSameAs(filter);
    }

    @Test
    void shouldExtendLookupKeysAlsoWhenFiltersAreNotPushedDown() {
        var recheck = recheckWithColumns(FilterHandlingPolicy.NEVER);

        var lookupKeys = recheck.lookupKeys(new int[][] {{1}}, PHYSICAL_ROW);

        assertThat(lookupKeys.conditions(DIALECT)).containsExactly(NAME_KEY_CONDITION);
    }

    @Test
    void shouldNotReadMetadataWithoutStringColumns() {
        var recheck = new IgnoredKeywordRecheck(true, FilterHandlingPolicy.ALWAYS, unreachableOptions());
        var flagFilter =
                SourceTableUtils.call(
                        BuiltInFunctionDefinitions.EQUALS,
                        SourceTableUtils.field(FLAG, DataTypes.BOOLEAN(), 2),
                        SourceTableUtils.literal(true));

        var extendedFilters = recheck.extendFilters(List.of(flagFilter));
        var lookupKeys = recheck.lookupKeys(new int[][] {{2}}, PHYSICAL_ROW);

        assertThat(extendedFilters.filters()).singleElement().isSameAs(flagFilter);
        assertThat(lookupKeys.conditions(DIALECT)).containsExactly("\"flag\" = ?");
    }

    @Test
    void shouldFailWhenMetadataCannotBeRead() {
        var recheck = new IgnoredKeywordRecheck(true, FilterHandlingPolicy.ALWAYS, unreachableOptions());

        assertThatThrownBy(() -> recheck.extendFilters(List.of(SourceTableUtils.equalsFilter(NAME, 1, "x"))))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Could not read the columns of Elasticsearch table 'idx'");
        assertThatThrownBy(() -> recheck.lookupKeys(new int[][] {{1}}, PHYSICAL_ROW))
                .isInstanceOf(ValidationException.class);
    }

    private static IgnoredKeywordRecheck recheckWithColumns(FilterHandlingPolicy filterHandlingPolicy) {
        var recheck = new IgnoredKeywordRecheck(true, filterHandlingPolicy, unreachableOptions());
        recheck.setRecheckColumns(Map.of(NAME, NAME + ".keyword"));
        return recheck;
    }

    private static InternalJdbcConnectionOptions unreachableOptions() {
        return InternalJdbcConnectionOptions.builder()
                .setDBUrl("jdbc:elasticsearch://localhost:1")
                .setTableName("idx")
                .setDialect(DIALECT)
                .build();
    }
}
