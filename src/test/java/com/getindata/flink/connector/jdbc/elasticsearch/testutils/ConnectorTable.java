package com.getindata.flink.connector.jdbc.elasticsearch.testutils;

import lombok.Builder;
import lombok.NonNull;
import org.apache.flink.table.api.TableEnvironment;

@Builder
public class ConnectorTable {

    @NonNull private final String connector;
    @NonNull private final String name;
    @NonNull private final String columns;
    @NonNull private final String url;
    @NonNull private final String index;
    @NonNull private final String username;
    @NonNull private final String password;
    @Builder.Default private final String options = "";

    public void createIn(TableEnvironment env) {
        env.executeSql("""
                CREATE TABLE %s (%s) WITH (
                  'connector' = '%s', 'url' = '%s', 'table-name' = '%s',
                  'username' = '%s', 'password' = '%s'%s)""".formatted(
                name, columns, connector, url, index, username, password, options));
    }
}
