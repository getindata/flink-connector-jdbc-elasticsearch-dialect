package com.getindata.flink.connector.jdbc.elasticsearch.table;

import com.getindata.flink.connector.jdbc.elasticsearch.database.dialect.ElasticsearchDialect;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.data.GenericRowData;
import org.apache.flink.table.data.StringData;
import org.apache.flink.table.types.DataType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
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

    private static final FilterExtender EXTENDER =
            new FilterExtender(() -> Map.of(NAME, NAME + ".keyword"));

    @Test
    void shouldExtendOnlyConditionsOfKeysOnRecheckColumns() {
        var lookupKeys = LookupKeys.of(new int[][] {{0}, {1}}, PHYSICAL_ROW, EXTENDER);

        assertThat(lookupKeys.conditions(new ElasticsearchDialect()))
                .containsExactly("\"referenceId\" = ?", """
                        ("name" = ? OR (("name.keyword" IS NULL) AND ("name" IS NOT NULL)))""");
    }

    @Test
    void shouldNotExtendConditionsWithNoneExtender() {
        var lookupKeys = LookupKeys.of(new int[][] {{0}, {1}}, PHYSICAL_ROW, FilterExtender.NONE);

        assertThat(lookupKeys.conditions(new ElasticsearchDialect()))
                .containsExactly("\"referenceId\" = ?", "\"name\" = ?");
    }

    @Test
    void shouldRecheckOnlyExtendedKeys() {
        var lookupFunction = mock(ElasticsearchRowDataLookupFunction.class);
        var lookupKeys = LookupKeys.of(new int[][] {{1}, {0}}, PHYSICAL_ROW, EXTENDER);

        var wrapped = lookupKeys.wrap(lookupFunction);

        assertThat(wrapped).isInstanceOf(RecheckedKeysLookupFunction.class);
        var rechecked = (RecheckedKeysLookupFunction<?>) wrapped;
        assertThat(rechecked.getDelegate()).isSameAs(lookupFunction);
        var keyRow = GenericRowData.of(StringData.fromString("x"), StringData.fromString("ref-1"));
        assertThat(rechecked.getRecheckedKeys())
                .singleElement()
                .satisfies(key -> {
                    assertThat(key.matches(keyRow, row("other", "x"))).isTrue();
                    assertThat(key.matches(keyRow, row("ref-1", "y"))).isFalse();
                });
    }

    @Test
    void shouldNotWrapLookupFunctionWithoutExtendedKeys() {
        var lookupFunction = mock(ElasticsearchRowDataLookupFunction.class);
        var lookupKeys = LookupKeys.of(new int[][] {{0}, {2}}, PHYSICAL_ROW, EXTENDER);

        assertThat(lookupKeys.wrap(lookupFunction)).isSameAs(lookupFunction);
    }

    private static GenericRowData row(String referenceId, String name) {
        return GenericRowData.of(StringData.fromString(referenceId), StringData.fromString(name), true);
    }
}
