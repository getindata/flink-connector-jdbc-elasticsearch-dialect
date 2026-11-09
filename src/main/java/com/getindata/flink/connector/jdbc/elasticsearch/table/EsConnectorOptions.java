package com.getindata.flink.connector.jdbc.elasticsearch.table;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.apache.flink.configuration.ConfigOption;
import org.apache.flink.configuration.ConfigOptions;
import org.apache.flink.configuration.ReadableConfig;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class EsConnectorOptions {

    private static final String RECHECK_DESCRIPTION = """
            Prevents losing documents whose 'keyword' subfield was ignored (e.g. 'ignore_above') \
            when filters on 'text' columns are pushed down; such filters are checked again in Flink. \
            Not supported for cross-cluster tables.""";

    public static final ConfigOption<Boolean> FILTER_IGNORED_KEYWORD_RECHECK =
            ConfigOptions.key("filter.ignored-keyword.recheck")
                    .booleanType()
                    .noDefaultValue()
                    .withDescription("""
                            %s Overrides '%s.filter.ignored-keyword.recheck'. Default: true."""
                            .formatted(RECHECK_DESCRIPTION, ElasticsearchDynamicTableFactory.IDENTIFIER));

    public static final ConfigOption<Boolean> SESSION_FILTER_IGNORED_KEYWORD_RECHECK =
            ConfigOptions.key(ElasticsearchDynamicTableFactory.IDENTIFIER + ".filter.ignored-keyword.recheck")
                    .booleanType()
                    .defaultValue(true)
                    .withDescription("""
                            %s Session default for 'filter.ignored-keyword.recheck'."""
                            .formatted(RECHECK_DESCRIPTION));

    public static boolean resolveRecheck(ReadableConfig tableConfig, ReadableConfig sessionConfig) {
        return tableConfig
                .getOptional(FILTER_IGNORED_KEYWORD_RECHECK)
                .orElseGet(() -> sessionConfig.get(SESSION_FILTER_IGNORED_KEYWORD_RECHECK));
    }
}
