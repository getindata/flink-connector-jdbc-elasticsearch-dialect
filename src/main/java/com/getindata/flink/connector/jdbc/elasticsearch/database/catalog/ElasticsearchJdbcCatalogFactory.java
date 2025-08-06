package com.getindata.flink.connector.jdbc.elasticsearch.database.catalog;


import org.apache.flink.configuration.ConfigOption;
import org.apache.flink.table.catalog.Catalog;
import org.apache.flink.table.factories.CatalogFactory;
import org.apache.flink.table.factories.FactoryUtil;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.getindata.flink.connector.jdbc.elasticsearch.database.catalog.ElasticsearchJdbcCatalogFactoryOptions.ADD_PROCTIME_COLUMN;
import static com.getindata.flink.connector.jdbc.elasticsearch.database.catalog.ElasticsearchJdbcCatalogFactoryOptions.BASE_URL;
import static com.getindata.flink.connector.jdbc.elasticsearch.database.catalog.ElasticsearchJdbcCatalogFactoryOptions.DEFAULT_DATABASE;
import static com.getindata.flink.connector.jdbc.elasticsearch.database.catalog.ElasticsearchJdbcCatalogFactoryOptions.DEFAULT_SCAN_PARTITION_COLUMN_NAME;
import static com.getindata.flink.connector.jdbc.elasticsearch.database.catalog.ElasticsearchJdbcCatalogFactoryOptions.DEFAULT_SCAN_PARTITION_SIZE;
import static com.getindata.flink.connector.jdbc.elasticsearch.database.catalog.ElasticsearchJdbcCatalogFactoryOptions.EXCLUDE;
import static com.getindata.flink.connector.jdbc.elasticsearch.database.catalog.ElasticsearchJdbcCatalogFactoryOptions.IDENTIFIER;
import static com.getindata.flink.connector.jdbc.elasticsearch.database.catalog.ElasticsearchJdbcCatalogFactoryOptions.INCLUDE;
import static com.getindata.flink.connector.jdbc.elasticsearch.database.catalog.ElasticsearchJdbcCatalogFactoryOptions.PASSWORD;
import static com.getindata.flink.connector.jdbc.elasticsearch.database.catalog.ElasticsearchJdbcCatalogFactoryOptions.PROPERTIES_INDEX_PATTERNS;
import static com.getindata.flink.connector.jdbc.elasticsearch.database.catalog.ElasticsearchJdbcCatalogFactoryOptions.USERNAME;

public class ElasticsearchJdbcCatalogFactory implements CatalogFactory {

    @Override
    public String factoryIdentifier() {
        return IDENTIFIER;
    }

    @Override
    public Set<ConfigOption<?>> requiredOptions() {
        final Set<ConfigOption<?>> options = new HashSet<>();
        options.add(DEFAULT_DATABASE);
        options.add(USERNAME);
        options.add(PASSWORD);
        options.add(BASE_URL);
        return options;
    }

    @Override
    public Set<ConfigOption<?>> optionalOptions() {
        final Set<ConfigOption<?>> options = new HashSet<>();
        options.add(DEFAULT_SCAN_PARTITION_COLUMN_NAME);
        options.add(DEFAULT_SCAN_PARTITION_SIZE);
        options.add(PROPERTIES_INDEX_PATTERNS);
        options.add(ADD_PROCTIME_COLUMN);
        options.add(EXCLUDE);
        options.add(INCLUDE);
        return options;
    }

    @Override
    public Catalog createCatalog(Context context) {
        final FactoryUtil.CatalogFactoryHelper helper =
                FactoryUtil.createCatalogFactoryHelper(this, context);
        helper.validateExcept("properties.scan");
        validateDynamicOptions(context.getOptions());

        return new ElasticsearchCatalog(
                context.getClassLoader(),
                context.getName(),
                helper.getOptions().get(DEFAULT_DATABASE),
                helper.getOptions().get(USERNAME),
                helper.getOptions().get(PASSWORD),
                helper.getOptions().get(BASE_URL),
                Boolean.getBoolean(context.getOptions().get(ADD_PROCTIME_COLUMN)),
                IndexFilterResolver.of(helper.getOptions().get(INCLUDE), helper.getOptions().get(EXCLUDE)),
                context.getOptions()
        );
    }

    private void validateDynamicOptions(Map<String, String> options) {
        Map<String, String> scanOptions = extractScanOptions(options);
        for (Map.Entry<String, String> entry : scanOptions.entrySet()) {
            String key = entry.getKey();
            if (!(key.startsWith("properties.scan.") && key.endsWith("partition.column.name")) &&
                    !(key.startsWith("properties.scan.") && key.endsWith("partition.number"))) {
                throw new IllegalArgumentException("Parameter " + entry.getKey() + " is not supported." +
                        " We support only the following dynamic properties:\n" +
                        " properties.scan.<table_name>.partition.column.name\n" +
                        " properties.scan.<table_name>.partition.number"
                );
            }
        }
    }

    private Map<String, String> extractScanOptions(Map<String, String> options) {
        return options.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("properties.scan"))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}