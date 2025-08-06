package com.getindata.flink.connector.jdbc.elasticsearch.database.catalog;


import org.apache.flink.annotation.Internal;
import org.apache.flink.configuration.ConfigOption;
import org.apache.flink.configuration.ConfigOptions;
import org.apache.flink.table.catalog.CommonCatalogOptions;

@Internal
public class ElasticsearchJdbcCatalogFactoryOptions {
    public static final String IDENTIFIER = "elasticsearch";

    public static final ConfigOption<String> DEFAULT_DATABASE =
            ConfigOptions.key(CommonCatalogOptions.DEFAULT_DATABASE_KEY)
                    .stringType()
                    .noDefaultValue()
                    .withDescription("Default database name to use.");

    public static final ConfigOption<String> USERNAME =
            ConfigOptions.key("username")
                    .stringType()
                    .noDefaultValue()
                    .withDescription("ElasticSearch Username to use.");

    public static final ConfigOption<String> PASSWORD =
            ConfigOptions.key("password")
                    .stringType()
                    .noDefaultValue()
                    .withDescription("ElasticSearch Password to use.");

    public static final ConfigOption<String> BASE_URL =
            ConfigOptions.key("base-url")
                    .stringType()
                    .noDefaultValue()
                    .withDescription("Url of ElasticSearch.");

    public static final ConfigOption<String> DEFAULT_SCAN_PARTITION_COLUMN_NAME =
            ConfigOptions.key("catalog.default.scan.partition.column.name")
                    .stringType()
                    .noDefaultValue()
                    .withDescription(
                            "Catalog default scan partition column name.");

    public static final ConfigOption<Integer> DEFAULT_SCAN_PARTITION_SIZE =
            ConfigOptions.key("catalog.default.scan.partition.size")
                    .intType()
                    .noDefaultValue()
                    .withDescription(
                            "Catalog default scan partition size.");

    public static final ConfigOption<String> PROPERTIES_INDEX_PATTERNS =
            ConfigOptions.key("properties.index.patterns")
                    .stringType()
                    .noDefaultValue()
                    .withDescription(
                            "Index patterns.");

    public static final ConfigOption<Boolean> ADD_PROCTIME_COLUMN =
            ConfigOptions.key("catalog.add-proctime-column")
                    .booleanType()
                    .defaultValue(false)
                    .withDescription("Indicates if proctime column should be added to all tables.");

    public static final ConfigOption<String> EXCLUDE =
            ConfigOptions.key("catalog.exclude")
                    .stringType()
                    .noDefaultValue()
                    .withDescription("Comma-separated list of index/datastream exclude patterns.");

    public static final ConfigOption<String> INCLUDE =
            ConfigOptions.key("catalog.include")
                    .stringType()
                    .noDefaultValue()
                    .withDescription("Comma-separated list of index/datastream include patterns.");

    private ElasticsearchJdbcCatalogFactoryOptions() {
    }
}
