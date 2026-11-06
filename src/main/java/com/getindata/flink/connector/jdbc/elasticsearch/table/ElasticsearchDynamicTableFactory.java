/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.getindata.flink.connector.jdbc.elasticsearch.table;

import org.apache.flink.configuration.ReadableConfig;
import org.apache.flink.connector.jdbc.core.database.JdbcFactoryLoader;
import org.apache.flink.connector.jdbc.core.table.JdbcDynamicTableFactory;
import org.apache.flink.connector.jdbc.internal.options.InternalJdbcConnectionOptions;
import org.apache.flink.connector.jdbc.internal.options.JdbcReadOptions;
import org.apache.flink.table.connector.source.DynamicTableSource;
import org.apache.flink.table.connector.source.lookup.LookupOptions;
import org.apache.flink.table.connector.source.lookup.cache.DefaultLookupCache;
import org.apache.flink.table.connector.source.lookup.cache.LookupCache;
import org.apache.flink.table.factories.FactoryUtil;

import javax.annotation.Nullable;
import java.time.Duration;
import java.util.Optional;

import static org.apache.flink.connector.jdbc.core.table.JdbcConnectorOptions.COMPATIBLE_MODE;
import static org.apache.flink.connector.jdbc.core.table.JdbcConnectorOptions.DRIVER;
import static org.apache.flink.connector.jdbc.core.table.JdbcConnectorOptions.FILTER_HANDLING_POLICY;
import static org.apache.flink.connector.jdbc.core.table.JdbcConnectorOptions.LOOKUP_CACHE_MAX_ROWS;
import static org.apache.flink.connector.jdbc.core.table.JdbcConnectorOptions.LOOKUP_CACHE_MISSING_KEY;
import static org.apache.flink.connector.jdbc.core.table.JdbcConnectorOptions.LOOKUP_CACHE_TTL;
import static org.apache.flink.connector.jdbc.core.table.JdbcConnectorOptions.MAX_RETRY_TIMEOUT;
import static org.apache.flink.connector.jdbc.core.table.JdbcConnectorOptions.PASSWORD;
import static org.apache.flink.connector.jdbc.core.table.JdbcConnectorOptions.SCAN_AUTO_COMMIT;
import static org.apache.flink.connector.jdbc.core.table.JdbcConnectorOptions.SCAN_FETCH_SIZE;
import static org.apache.flink.connector.jdbc.core.table.JdbcConnectorOptions.SCAN_PARTITION_COLUMN;
import static org.apache.flink.connector.jdbc.core.table.JdbcConnectorOptions.SCAN_PARTITION_LOWER_BOUND;
import static org.apache.flink.connector.jdbc.core.table.JdbcConnectorOptions.SCAN_PARTITION_NUM;
import static org.apache.flink.connector.jdbc.core.table.JdbcConnectorOptions.SCAN_PARTITION_UPPER_BOUND;
import static org.apache.flink.connector.jdbc.core.table.JdbcConnectorOptions.SINK_PARALLELISM;
import static org.apache.flink.connector.jdbc.core.table.JdbcConnectorOptions.TABLE_NAME;
import static org.apache.flink.connector.jdbc.core.table.JdbcConnectorOptions.URL;
import static org.apache.flink.connector.jdbc.core.table.JdbcConnectorOptions.USERNAME;
import static org.apache.flink.connector.jdbc.utils.JdbcUtils.getConnectionProperties;

public class ElasticsearchDynamicTableFactory extends JdbcDynamicTableFactory {

    public static final String IDENTIFIER = "elasticsearch-jdbc";

    @Override
    public String factoryIdentifier() {
        return IDENTIFIER;
    }

    @Override
    public DynamicTableSource createDynamicTableSource(Context context) {
        // options validation and the schema against the dialect
        super.createDynamicTableSource(context);

        final ReadableConfig config = FactoryUtil.createTableFactoryHelper(this, context).getOptions();
        return new ElasticsearchDynamicTableSource(
                getJdbcOptions(config, context.getClassLoader()),
                getJdbcReadOptions(config),
                config.get(LookupOptions.MAX_RETRIES),
                getLookupCache(config),
                config.get(FILTER_HANDLING_POLICY),
                context.getPhysicalRowDataType());
    }

    // Copied from parent - private
    private static InternalJdbcConnectionOptions getJdbcOptions(
            ReadableConfig readableConfig, ClassLoader classLoader) {
        final String url = readableConfig.get(URL);
        final InternalJdbcConnectionOptions.Builder builder =
                InternalJdbcConnectionOptions.builder()
                        .setClassLoader(classLoader)
                        .setDBUrl(url)
                        .setTableName(readableConfig.get(TABLE_NAME))
                        .setDialect(
                                JdbcFactoryLoader.loadDialect(
                                        url, classLoader, readableConfig.get(COMPATIBLE_MODE)))
                        .setParallelism(readableConfig.getOptional(SINK_PARALLELISM).orElse(null))
                        .setConnectionCheckTimeoutSeconds(
                                (int) readableConfig.get(MAX_RETRY_TIMEOUT).getSeconds());

        readableConfig.getOptional(DRIVER).ifPresent(builder::setDriverName);
        readableConfig.getOptional(USERNAME).ifPresent(builder::setUsername);
        readableConfig.getOptional(PASSWORD).ifPresent(builder::setPassword);
        readableConfig.getOptional(COMPATIBLE_MODE).ifPresent(builder::setCompatibleMode);
        getConnectionProperties(readableConfig)
                .forEach((key, value) -> builder.setProperty(key.toString(), value.toString()));
        return builder.build();
    }

    // Copied from parent - private
    private static JdbcReadOptions getJdbcReadOptions(ReadableConfig readableConfig) {
        final Optional<String> partitionColumnName =
                readableConfig.getOptional(SCAN_PARTITION_COLUMN);
        final JdbcReadOptions.Builder builder = JdbcReadOptions.builder();
        if (partitionColumnName.isPresent()) {
            builder.setPartitionColumnName(partitionColumnName.get());
            builder.setPartitionLowerBound(readableConfig.get(SCAN_PARTITION_LOWER_BOUND));
            builder.setPartitionUpperBound(readableConfig.get(SCAN_PARTITION_UPPER_BOUND));
            builder.setNumPartitions(readableConfig.get(SCAN_PARTITION_NUM));
        }
        readableConfig.getOptional(SCAN_FETCH_SIZE).ifPresent(builder::setFetchSize);
        builder.setAutoCommit(readableConfig.get(SCAN_AUTO_COMMIT));
        return builder.build();
    }

    // Copied from parent - private
    @Nullable
    private LookupCache getLookupCache(ReadableConfig tableOptions) {
        LookupCache cache = null;
        if (tableOptions.get(LOOKUP_CACHE_MAX_ROWS) > 0
                && tableOptions.get(LOOKUP_CACHE_TTL).compareTo(Duration.ZERO) > 0) {
            cache =
                    DefaultLookupCache.newBuilder()
                            .maximumSize(tableOptions.get(LOOKUP_CACHE_MAX_ROWS))
                            .expireAfterWrite(tableOptions.get(LOOKUP_CACHE_TTL))
                            .cacheMissingKey(tableOptions.get(LOOKUP_CACHE_MISSING_KEY))
                            .build();
        }
        if (tableOptions
                .get(LookupOptions.CACHE_TYPE)
                .equals(LookupOptions.LookupCacheType.PARTIAL)) {
            cache = DefaultLookupCache.fromConfig(tableOptions);
        }
        return cache;
    }

}
