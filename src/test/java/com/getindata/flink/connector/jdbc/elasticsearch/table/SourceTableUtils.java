package com.getindata.flink.connector.jdbc.elasticsearch.table;

import com.getindata.flink.connector.jdbc.elasticsearch.database.dialect.ElasticsearchDialect;
import lombok.experimental.UtilityClass;
import org.apache.flink.connector.jdbc.core.datastream.source.JdbcSource;
import org.apache.flink.connector.jdbc.core.table.source.JdbcFilterPushdownPreparedStatementVisitor;
import org.apache.flink.connector.jdbc.core.table.source.ParameterizedPredicate;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.transformations.SourceTransformation;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.catalog.ResolvedSchema;
import org.apache.flink.table.connector.source.DataStreamScanProvider;
import org.apache.flink.table.connector.source.lookup.LookupFunctionProvider;
import org.apache.flink.table.expressions.CallExpression;
import org.apache.flink.table.expressions.FieldReferenceExpression;
import org.apache.flink.table.expressions.ResolvedExpression;
import org.apache.flink.table.expressions.ValueLiteralExpression;
import org.apache.flink.table.factories.utils.FactoryMocks;
import org.apache.flink.table.functions.BuiltInFunctionDefinition;
import org.apache.flink.table.functions.BuiltInFunctionDefinitions;
import org.apache.flink.table.functions.LookupFunction;
import org.apache.flink.table.runtime.connector.source.LookupRuntimeProviderContext;
import org.apache.flink.table.runtime.connector.source.ScanRuntimeProviderContext;
import org.apache.flink.table.types.DataType;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@UtilityClass
class SourceTableUtils {

    CallExpression call(BuiltInFunctionDefinition function, ResolvedExpression... args) {
        return CallExpression.permanent(function, List.of(args), DataTypes.BOOLEAN());
    }

    CallExpression equalsFilter(String column, int index, String value) {
        return call(BuiltInFunctionDefinitions.EQUALS, stringField(column, index), literal(value));
    }

    CallExpression upperEquals(String column, int index, String value) {
        var upper =
                CallExpression.permanent(
                        BuiltInFunctionDefinitions.UPPER, List.of(stringField(column, index)), DataTypes.STRING());
        return call(BuiltInFunctionDefinitions.EQUALS, upper, literal(value));
    }

    FieldReferenceExpression stringField(String name, int index) {
        return field(name, DataTypes.STRING(), index);
    }

    FieldReferenceExpression field(String name, DataType type, int index) {
        return new FieldReferenceExpression(name, type, 0, index);
    }

    ValueLiteralExpression literal(Object value) {
        return new ValueLiteralExpression(value);
    }

    ParameterizedPredicate translate(ResolvedExpression filter) {
        var predicate = translateIfSupported(filter);
        assertThat(predicate).isPresent();
        return predicate.get();
    }

    Optional<ParameterizedPredicate> translateIfSupported(ResolvedExpression filter) {
        var upstreamVisitor = new JdbcFilterPushdownPreparedStatementVisitor(new ElasticsearchDialect()::quoteIdentifier);
        return ((CallExpression) filter).accept(upstreamVisitor);
    }

    ElasticsearchDynamicTableSource createSource(ResolvedSchema schema, Map<String, String> options) {
        return (ElasticsearchDynamicTableSource) FactoryMocks.createTableSource(schema, options);
    }

    LookupRuntimeProviderContext lookupContext(int... keyIndexes) {
        var keys = Arrays.stream(keyIndexes).mapToObj(keyIndex -> new int[] {keyIndex}).toArray(int[][]::new);
        return new LookupRuntimeProviderContext(keys, false);
    }

    LookupFunction runtimeLookupFunction(ElasticsearchDynamicTableSource source, int... keyIndexes) {
        var provider = source.getLookupRuntimeProvider(lookupContext(keyIndexes));
        assertThat(provider).isInstanceOf(LookupFunctionProvider.class);
        return ((LookupFunctionProvider) provider).createLookupFunction();
    }

    ElasticsearchRowDataLookupFunction lookupFunction(ElasticsearchDynamicTableSource source, int keyIndex) {
        var function = runtimeLookupFunction(source, keyIndex);
        if (function instanceof RecheckedKeysLookupFunction<?> rechecked) {
            return (ElasticsearchRowDataLookupFunction) rechecked.getDelegate();
        }
        return (ElasticsearchRowDataLookupFunction) function;
    }

    String scanSql(ElasticsearchDynamicTableSource source) {
        var provider = (DataStreamScanProvider) source.getScanRuntimeProvider(ScanRuntimeProviderContext.INSTANCE);
        var stream =
                provider.produceDataStream(name -> Optional.empty(), StreamExecutionEnvironment.getExecutionEnvironment());
        var jdbcSource = (JdbcSource<?>) ((SourceTransformation<?, ?, ?>) stream.getTransformation()).getSource();
        return jdbcSource.getSplitterEnumerator().lineageQueries().get(0);
    }
}
