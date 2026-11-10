package com.getindata.flink.connector.jdbc.elasticsearch.table;

import com.getindata.flink.connector.jdbc.elasticsearch.database.dialect.ElasticsearchDialect;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IgnoredKeywordConditionTest {

    private static final ElasticsearchDialect DIALECT = new ElasticsearchDialect();

    @Test
    void shouldRenderSql() {
        assertThat(IgnoredKeywordCondition.sql(DIALECT, "a\"b", "a\"b.keyword"))
                .isEqualTo("""
                        (("a""b.keyword" IS NULL) AND ("a""b" IS NOT NULL))""");
    }

    @Test
    void shouldPushDownExpressionAsSameSql() {
        var expression = IgnoredKeywordCondition.expression(SourceTableUtils.stringField("geo:loc", 3), "geo:loc.raw");

        var predicate = SourceTableUtils.translate(expression);

        assertThat(predicate.getPredicate()).isEqualTo(IgnoredKeywordCondition.sql(DIALECT, "geo:loc", "geo:loc.raw"));
        assertThat(predicate.getParameters()).isEmpty();
    }
}
