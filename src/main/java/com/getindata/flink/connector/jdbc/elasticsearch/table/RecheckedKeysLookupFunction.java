package com.getindata.flink.connector.jdbc.elasticsearch.table;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.streaming.api.lineage.LineageVertex;
import org.apache.flink.streaming.api.lineage.LineageVertexProvider;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.functions.FunctionContext;
import org.apache.flink.table.functions.LookupFunction;
import org.apache.flink.table.types.logical.LogicalType;

import java.io.IOException;
import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

@RequiredArgsConstructor
public class RecheckedKeysLookupFunction<T extends LookupFunction & LineageVertexProvider> extends LookupFunction
        implements LineageVertexProvider {

    @Serial
    private static final long serialVersionUID = 1L;

    @NonNull
    private final T delegate;
    @NonNull
    private final List<RecheckedKey> recheckedKeys;

    @VisibleForTesting
    T getDelegate() {
        return delegate;
    }

    @VisibleForTesting
    List<RecheckedKey> getRecheckedKeys() {
        return recheckedKeys;
    }

    @Override
    public void open(FunctionContext context) throws Exception {
        delegate.open(context);
    }

    @Override
    public Collection<RowData> lookup(RowData keyRow) throws IOException {
        return delegate.lookup(keyRow).stream().filter(row -> matchesKeys(keyRow, row)).toList();
    }

    private boolean matchesKeys(RowData keyRow, RowData row) {
        return recheckedKeys.stream().allMatch(key -> key.matches(keyRow, row));
    }

    @Override
    public void close() throws Exception {
        delegate.close();
    }

    @Override
    public LineageVertex getLineageVertex() {
        return delegate.getLineageVertex();
    }

    public record RecheckedKey(RowData.FieldGetter inKeyRow, RowData.FieldGetter inRow) implements Serializable {

        static RecheckedKey of(LogicalType type, int keyPosition, int fieldPosition) {
            return new RecheckedKey(
                    RowData.createFieldGetter(type, keyPosition), RowData.createFieldGetter(type, fieldPosition));
        }

        boolean matches(RowData keyRow, RowData row) {
            return Objects.equals(inKeyRow.getFieldOrNull(keyRow), inRow.getFieldOrNull(row));
        }
    }
}
