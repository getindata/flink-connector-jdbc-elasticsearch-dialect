package com.getindata.flink.connector.jdbc.elasticsearch.table;

import org.apache.flink.streaming.api.lineage.LineageVertex;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.data.GenericRowData;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.data.StringData;
import org.apache.flink.table.functions.FunctionContext;
import org.apache.flink.table.types.logical.LogicalType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExactKeyMatchLookupFunctionTest {

    private static final LogicalType STRING = DataTypes.STRING().getLogicalType();

    private ElasticsearchRowDataLookupFunction delegate;

    @BeforeEach
    void setUp() {
        delegate = mock(ElasticsearchRowDataLookupFunction.class);
    }

    @Test
    void shouldDropRowsWhoseKeyDiffers() throws Exception {
        // key row (referenceId, version), rows (referenceId, productName, version)
        var function = new ExactKeyMatchLookupFunction<>(
                delegate, List.of(ExactKeyMatchLookupFunction.KeyMatch.of(STRING, 0, 0)));
        var keyRow = GenericRowData.of(StringData.fromString("ref-1"), 7);
        var matching = row("ref-1", 7);
        var otherVersion = row("ref-1", 8);
        when(delegate.lookup(keyRow)).thenReturn(List.of(matching, row("ref-2", 7), row(null, 7), otherVersion));

        var rows = function.lookup(keyRow);

        // the version is not compared, Elasticsearch already matched it
        assertThat(rows).containsExactly(matching, otherVersion);
    }

    @Test
    void shouldRequireAllKeysToMatch() throws Exception {
        var function = new ExactKeyMatchLookupFunction<>(
                delegate,
                List.of(ExactKeyMatchLookupFunction.KeyMatch.of(STRING, 0, 0),
                        ExactKeyMatchLookupFunction.KeyMatch.of(STRING, 1, 1)));
        var keyRow = GenericRowData.of(StringData.fromString("ref-1"), StringData.fromString("product"));
        var matching = row("ref-1", 7);
        var otherProduct = GenericRowData.of(StringData.fromString("ref-1"), StringData.fromString("other"), 7);
        when(delegate.lookup(keyRow)).thenReturn(List.of(matching, otherProduct));

        assertThat(function.lookup(keyRow)).containsExactly(matching);
    }

    @Test
    void shouldReadKeyAndRowAtTheirOwnPositions() {
        var key = ExactKeyMatchLookupFunction.KeyMatch.of(STRING, 0, 1);
        var keyRow = GenericRowData.of(StringData.fromString("product"));

        assertThat(key.matches(keyRow, row("other", 7))).isTrue();
        assertThat(key.matches(keyRow, GenericRowData.of(StringData.fromString("product"), null, 7))).isFalse();
    }

    @Test
    void shouldDelegateOpenCloseAndLineage() throws Exception {
        var function = new ExactKeyMatchLookupFunction<>(delegate, List.of());
        var context = mock(FunctionContext.class);
        var vertex = mock(LineageVertex.class);
        when(delegate.getLineageVertex()).thenReturn(vertex);

        function.open(context);
        function.close();

        verify(delegate).open(context);
        verify(delegate).close();
        assertThat(function.getLineageVertex()).isSameAs(vertex);
    }

    private static RowData row(String referenceId, int version) {
        return GenericRowData.of(nullableString(referenceId), StringData.fromString("product"), version);
    }

    private static StringData nullableString(String value) {
        if (value == null) {
            return null;
        }
        return StringData.fromString(value);
    }
}
