package com.getindata.flink.connector.jdbc.elasticsearch.table;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.apache.flink.table.api.ValidationException;
import org.elasticsearch.xpack.sql.jdbc.JdbcConfiguration;

import java.sql.SQLException;
import java.util.Locale;
import java.util.Properties;

/** Makes the Elasticsearch JDBC driver fail on multi-valued fields instead of returning their first value. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class StrictMultiValue {

    static final String LENIENCY = "field.multi.value.leniency";

    static Properties enforce(String url, Properties connectionProperties) {
        if (configuredByUser(url, connectionProperties) && lenient(url, connectionProperties)) {
            throw new ValidationException(
                    "'%1$s' = '%2$s' cannot be used with '%3$s' = '%4$s', remove it or set '%3$s' = '%5$s'."
                            .formatted(LENIENCY, Boolean.TRUE, EsConnectorOptions.FILTER_IGNORED_KEYWORD_HANDLING.key(),
                                    optionValue(IgnoredKeywordHandling.RECHECK),
                                    optionValue(IgnoredKeywordHandling.NONE)));
        }
        var strict = new Properties();
        strict.putAll(connectionProperties);
        strict.setProperty(LENIENCY, Boolean.FALSE.toString());
        return strict;
    }

    private static boolean configuredByUser(String url, Properties connectionProperties) {
        return connectionProperties.containsKey(LENIENCY) || url.contains(LENIENCY);
    }

    private static boolean lenient(String url, Properties connectionProperties) {
        try {
            return JdbcConfiguration.create(url, connectionProperties, 0).fieldMultiValueLeniency();
        } catch (SQLException invalidConfiguration) {
            throw new ValidationException("Cannot read '%s' from the JDBC URL and the connection properties: %s"
                    .formatted(LENIENCY, invalidConfiguration.getMessage()), invalidConfiguration);
        }
    }

    private static String optionValue(IgnoredKeywordHandling handling) {
        return handling.name().toLowerCase(Locale.ROOT);
    }
}
