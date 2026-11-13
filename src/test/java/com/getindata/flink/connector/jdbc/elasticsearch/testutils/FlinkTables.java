package com.getindata.flink.connector.jdbc.elasticsearch.testutils;

import lombok.experimental.UtilityClass;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.planner.factories.TestValuesTableFactory;
import org.apache.flink.types.Row;
import org.apache.flink.util.CollectionUtil;

import java.util.List;

@UtilityClass
public class FlinkTables {

    public List<Row> collect(TableEnvironment env, String sql) {
        return CollectionUtil.iteratorToList(env.executeSql(sql).collect());
    }

    public void createLookupProbeTable(TableEnvironment env, String name, String columns, List<Row> rows) {
        var dataId = TestValuesTableFactory.registerData(rows);
        env.executeSql("""
                CREATE TABLE %s (%s, proctime AS PROCTIME())
                WITH ('connector' = 'values', 'data-id' = '%s')""".formatted(name, columns, dataId));
    }

    public String partialCacheOptions(boolean enabled) {
        if (!enabled) {
            return "";
        }
        return ", 'lookup.cache' = 'PARTIAL', 'lookup.partial-cache.max-rows' = '100'";
    }
}
