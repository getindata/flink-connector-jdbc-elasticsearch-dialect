package com.getindata.flink.connector.jdbc.elasticsearch.table;

import com.getindata.flink.connector.jdbc.elasticsearch.database.dialect.ElasticsearchDialect;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.api.ValidationException;
import org.apache.flink.table.data.GenericRowData;
import org.apache.flink.table.data.StringData;
import org.apache.flink.table.types.DataType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class LookupKeysTest {

    private static final String REFERENCE_ID = "referenceId";
    private static final String NAME = "name";
    private static final String FLAG = "flag";

    private static final DataType PHYSICAL_ROW =
            DataTypes.ROW(
                    DataTypes.FIELD(REFERENCE_ID, DataTypes.STRING()),
                    DataTypes.FIELD(NAME, DataTypes.STRING()),
                    DataTypes.FIELD(FLAG, DataTypes.BOOLEAN()));

    private static final KeywordSubfields NAME_KEYWORD_SUBFIELD = KeywordSubfields.of(Map.of(NAME, NAME + ".keyword"));

    @Test
    void shouldIncludeIgnoredKeywordsOnlyForKeysWithKeywordSubfield() {
        var lookupKeys = LookupKeys.of(new int[][] {{0}, {1}}, PHYSICAL_ROW, NAME_KEYWORD_SUBFIELD);

        assertThat(lookupKeys.conditions(new ElasticsearchDialect()))
                .containsExactly("\"referenceId\" = ?", """
                        ("name" = ? OR (("name.keyword" IS NULL) AND ("name" IS NOT NULL)))""");
    }

    @Test
    void shouldNotChangeConditionsWithoutKeywordSubfields() {
        var lookupKeys = LookupKeys.of(new int[][] {{0}, {1}}, PHYSICAL_ROW, KeywordSubfields.NONE);

        assertThat(lookupKeys.conditions(new ElasticsearchDialect()))
                .containsExactly("\"referenceId\" = ?", "\"name\" = ?");
    }

    @Test
    void shouldMatchExactlyOnlyKeysWithKeywordSubfield() {
        var lookupFunction = mock(ElasticsearchRowDataLookupFunction.class);
        var lookupKeys = LookupKeys.of(new int[][] {{1}, {0}}, PHYSICAL_ROW, NAME_KEYWORD_SUBFIELD);

        var lookupWithExactKeyMatch = lookupKeys.withExactKeyMatch(lookupFunction);

        assertThat(lookupWithExactKeyMatch).isInstanceOf(ExactKeyMatchLookupFunction.class);
        var exactKeyMatch = (ExactKeyMatchLookupFunction<?>) lookupWithExactKeyMatch;
        assertThat(exactKeyMatch.getDelegate()).isSameAs(lookupFunction);
        var keyRow = GenericRowData.of(StringData.fromString("x"), StringData.fromString("ref-1"));
        assertThat(exactKeyMatch.getKeyMatches())
                .singleElement()
                .satisfies(key -> {
                    assertThat(key.matches(keyRow, row("other", "x"))).isTrue();
                    assertThat(key.matches(keyRow, row("ref-1", "y"))).isFalse();
                });
    }

    @Test
    void shouldNotMatchExactlyWithoutKeysWithKeywordSubfield() {
        var lookupFunction = mock(ElasticsearchRowDataLookupFunction.class);
        var lookupKeys = LookupKeys.of(new int[][] {{0}, {2}}, PHYSICAL_ROW, NAME_KEYWORD_SUBFIELD);

        assertThat(lookupKeys.withExactKeyMatch(lookupFunction)).isSameAs(lookupFunction);
    }

    @Test
    void shouldReturnKeyTypesInLookupOrder() {
        var lookupKeys = LookupKeys.of(new int[][] {{2}, {0}}, PHYSICAL_ROW, KeywordSubfields.NONE);

        assertThat(lookupKeys.types())
                .containsExactly(DataTypes.BOOLEAN().getLogicalType(), DataTypes.STRING().getLogicalType());
    }

    @Test
    void shouldRejectNestedKeys() {
        assertThatThrownBy(() -> LookupKeys.of(new int[][] {{0, 1}}, PHYSICAL_ROW, KeywordSubfields.NONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("JDBC only support non-nested look up keys");
    }

    @Test
    void shouldNotReadMetadataWithoutStringKeys() {
        // reading from the unreachable Elasticsearch would fail
        var unreadable = KeywordSubfields.read(SourceTableUtils.unreachableOptions());

        var lookupKeys = LookupKeys.of(new int[][] {{2}}, PHYSICAL_ROW, unreadable);

        assertThat(lookupKeys.conditions(new ElasticsearchDialect())).containsExactly("\"flag\" = ?");
    }

    @Test
    void shouldFailWhenMetadataCannotBeRead() {
        var unreadable = KeywordSubfields.read(SourceTableUtils.unreachableOptions());

        assertThatThrownBy(() -> LookupKeys.of(new int[][] {{1}}, PHYSICAL_ROW, unreadable))
                .isInstanceOf(ValidationException.class);
    }

    private static GenericRowData row(String referenceId, String name) {
        return GenericRowData.of(StringData.fromString(referenceId), StringData.fromString(name), true);
    }
}
