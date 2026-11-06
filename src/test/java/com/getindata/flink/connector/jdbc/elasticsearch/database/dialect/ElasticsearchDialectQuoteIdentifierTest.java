package com.getindata.flink.connector.jdbc.elasticsearch.database.dialect;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class ElasticsearchDialectQuoteIdentifierTest {

    private final ElasticsearchDialect dialect = new ElasticsearchDialect();

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            quoteCharacter = '`',
            textBlock = """
                id                        | "id"
                order.details.referenceId | "order.details.referenceId"
                @timestamp                | "@timestamp"
                geo:loc                   | "geo:loc"
                what?                     | "what?"
                it's                      | "it's"
                `with space`              | "with space"
                a\\b                      | "a\\b"
                """)
    void shouldQuoteIdentifierWithoutQuotes(String identifier, String expected) {
        assertThat(dialect.quoteIdentifier(identifier)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            textBlock = """
                a"b        | "a""b"
                "leading   | ""\"leading"
                trailing"  | "trailing""\"
                a""b       | "a""\""b"
                "          | ""\""
                a"b:c?d    | "a""b:c?d"
                """)
    void shouldEscapeDoubleQuotesByDoublingThem(String identifier, String expected) {
        assertThat(dialect.quoteIdentifier(identifier)).isEqualTo(expected);
    }

    @Test
    void shouldUseEscapedIdentifiersInSelectStatement() {
        assertThat(dialect.getSelectFromStatement("idx", new String[] {"a\"b", "c"}, new String[0]))
                .isEqualTo("SELECT \"a\"\"b\", \"c\" FROM \"idx\"");
    }
}
