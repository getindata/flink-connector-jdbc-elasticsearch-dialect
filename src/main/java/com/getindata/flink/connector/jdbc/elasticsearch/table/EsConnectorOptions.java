package com.getindata.flink.connector.jdbc.elasticsearch.table;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.apache.flink.configuration.ConfigOption;
import org.apache.flink.configuration.ConfigOptions;
import org.apache.flink.configuration.ReadableConfig;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class EsConnectorOptions {

    private static final String HANDLING_DESCRIPTION = """
            How filters on 'text' columns are pushed down when their 'keyword' subfield may have been \
            ignored (e.g. 'ignore_above'), which would make Elasticsearch lose such documents. \
            Recheck is not supported for cross-cluster tables.""";

    public static final ConfigOption<IgnoredKeywordHandling> FILTER_IGNORED_KEYWORD_HANDLING =
            ConfigOptions.key("filter.ignored-keyword.handling")
                    .enumType(IgnoredKeywordHandling.class)
                    .noDefaultValue()
                    .withDescription("""
                            %s Overrides '%s.filter.ignored-keyword.handling'. Default: recheck."""
                            .formatted(HANDLING_DESCRIPTION, ElasticsearchDynamicTableFactory.IDENTIFIER));

    public static final ConfigOption<IgnoredKeywordHandling> SESSION_FILTER_IGNORED_KEYWORD_HANDLING =
            ConfigOptions.key(ElasticsearchDynamicTableFactory.IDENTIFIER + ".filter.ignored-keyword.handling")
                    .enumType(IgnoredKeywordHandling.class)
                    .defaultValue(IgnoredKeywordHandling.RECHECK)
                    .withDescription("""
                            %s Session default for 'filter.ignored-keyword.handling'."""
                            .formatted(HANDLING_DESCRIPTION));

    public static IgnoredKeywordHandling resolveHandling(ReadableConfig tableConfig, ReadableConfig sessionConfig) {
        return tableConfig
                .getOptional(FILTER_IGNORED_KEYWORD_HANDLING)
                .orElseGet(() -> sessionConfig.get(SESSION_FILTER_IGNORED_KEYWORD_HANDLING));
    }
}
