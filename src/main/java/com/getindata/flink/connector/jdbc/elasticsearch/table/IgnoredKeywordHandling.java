package com.getindata.flink.connector.jdbc.elasticsearch.table;

import lombok.Getter;
import org.apache.flink.configuration.DescribedEnum;
import org.apache.flink.configuration.description.InlineElement;

import static org.apache.flink.configuration.description.TextElement.text;

/** Handling of filters on 'text' columns whose 'keyword' subfield may have been ignored. */
@Getter
public enum IgnoredKeywordHandling implements DescribedEnum {

    RECHECK("""
            Pushed down filters also match documents with an ignored 'keyword' subfield and Flink \
            checks them again. Requires strict multi-value handling ('field.multi.value.leniency=false', \
            set by the connector)."""),
    NONE("Filters are pushed down unchanged, documents with an ignored 'keyword' subfield may be missing.");

    private final InlineElement description;

    IgnoredKeywordHandling(String description) {
        this.description = text(description);
    }
}
