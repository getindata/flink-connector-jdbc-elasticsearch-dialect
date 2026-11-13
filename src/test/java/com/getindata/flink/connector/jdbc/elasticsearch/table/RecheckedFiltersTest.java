package com.getindata.flink.connector.jdbc.elasticsearch.table;

import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.connector.source.abilities.SupportsFilterPushDown.Result;
import org.apache.flink.table.expressions.FieldReferenceExpression;
import org.apache.flink.table.expressions.ResolvedExpression;
import org.apache.flink.table.functions.BuiltInFunctionDefinition;
import org.apache.flink.table.functions.BuiltInFunctionDefinitions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class RecheckedFiltersTest {

    private static final String REFERENCE_ID = "referenceId";
    private static final String NAME = "name";

    private static final KeywordSubfields NAME_KEYWORD_SUBFIELD = KeywordSubfields.of(Map.of(NAME, NAME + ".keyword"));

    private static final KeywordSubfields KEYWORD_SUBFIELDS =
            KeywordSubfields.of(Map.of(
                    "RawXml", "RawXml.keyword",
                    "Name", "Name.raw",
                    "geo:loc", "geo:loc.keyword",
                    "a\"b", "a\"b.keyword",
                    // in the map, but declared as BIGINT in Flink
                    "num", "num.keyword"));

    private static final String RAW_XML_IGNORED = """
            (("RawXml.keyword" IS NULL) AND ("RawXml" IS NOT NULL))""";
    private static final String CAPITAL_NAME_IGNORED = """
            (("Name.raw" IS NULL) AND ("Name" IS NOT NULL))""";

    private static final FieldReferenceExpression RAW_XML = SourceTableUtils.stringField("RawXml", 0);
    private static final FieldReferenceExpression CAPITAL_NAME = SourceTableUtils.stringField("Name", 0);
    private static final FieldReferenceExpression KW = SourceTableUtils.stringField("kw", 0);
    private static final FieldReferenceExpression ID = SourceTableUtils.field("id", DataTypes.BIGINT(), 0);

    static Stream<Arguments> binaryOperators() {
        return Stream.of(
                Arguments.of(BuiltInFunctionDefinitions.EQUALS, "="),
                Arguments.of(BuiltInFunctionDefinitions.NOT_EQUALS, "<>"),
                Arguments.of(BuiltInFunctionDefinitions.LESS_THAN, "<"),
                Arguments.of(BuiltInFunctionDefinitions.LESS_THAN_OR_EQUAL, "<="),
                Arguments.of(BuiltInFunctionDefinitions.GREATER_THAN, ">"),
                Arguments.of(BuiltInFunctionDefinitions.GREATER_THAN_OR_EQUAL, ">="),
                Arguments.of(BuiltInFunctionDefinitions.LIKE, "LIKE"));
    }

    @ParameterizedTest
    @MethodSource("binaryOperators")
    void shouldIncludeIgnoredKeywordsInConditionOnColumnWithKeywordSubfield(BuiltInFunctionDefinition function, String operator) {
        var predicate = SourceTableUtils.translate(
                includingIgnoredKeywords(SourceTableUtils.call(function, RAW_XML, SourceTableUtils.literal("x"))));

        assertThat(predicate.getPredicate())
                .isEqualTo("((\"RawXml\" %s ?) OR %s)".formatted(operator, RAW_XML_IGNORED));
        assertThat(predicate.getParameters()).containsExactly("x");
    }

    @ParameterizedTest
    @MethodSource("binaryOperators")
    void shouldNotChangeConditionOnOtherColumn(BuiltInFunctionDefinition function, String operator) {
        var filter = SourceTableUtils.call(function, KW, SourceTableUtils.literal("x"));

        assertThat(includingIgnoredKeywords(filter)).isSameAs(filter);
    }

    @Test
    void shouldNotIncludeIgnoredKeywordsInIsNullAndIsNotNull() {
        var isNull = SourceTableUtils.call(BuiltInFunctionDefinitions.IS_NULL, RAW_XML);
        var isNotNull = SourceTableUtils.call(BuiltInFunctionDefinitions.IS_NOT_NULL, RAW_XML);
        var both = SourceTableUtils.call(BuiltInFunctionDefinitions.OR, isNull, isNotNull);

        assertThat(includingIgnoredKeywords(isNull)).isSameAs(isNull);
        assertThat(includingIgnoredKeywords(isNotNull)).isSameAs(isNotNull);
        assertThat(includingIgnoredKeywords(both)).isSameAs(both);
    }

    @Test
    void shouldIncludeIgnoredKeywordsInWholeAndOrContainingConditionOnColumnWithKeywordSubfield() {
        var filter =
                SourceTableUtils.call(
                        BuiltInFunctionDefinitions.OR,
                        SourceTableUtils.call(
                                BuiltInFunctionDefinitions.AND,
                                SourceTableUtils.call(
                                        BuiltInFunctionDefinitions.LIKE, RAW_XML, SourceTableUtils.literal("%a%")),
                                SourceTableUtils.call(
                                        BuiltInFunctionDefinitions.EQUALS, ID, SourceTableUtils.literal(5L))),
                        SourceTableUtils.call(
                                BuiltInFunctionDefinitions.EQUALS, KW, SourceTableUtils.literal("b")));

        var predicate = SourceTableUtils.translate(includingIgnoredKeywords(filter));

        assertThat(predicate.getPredicate())
                .isEqualTo("""
                        (((("RawXml" LIKE ?) AND ("id" = ?)) OR ("kw" = ?)) OR %s)"""
                        .formatted(RAW_XML_IGNORED));
        assertThat(predicate.getParameters()).containsExactly("%a%", 5L, "b");
    }

    @Test
    void shouldKeepAndOrWithoutColumnsWithKeywordSubfieldUnchanged() {
        var filter =
                SourceTableUtils.call(
                        BuiltInFunctionDefinitions.OR,
                        SourceTableUtils.call(BuiltInFunctionDefinitions.LIKE, KW, SourceTableUtils.literal("%a%")),
                        SourceTableUtils.call(
                                BuiltInFunctionDefinitions.IS_NULL, SourceTableUtils.stringField("other", 0)));

        assertThat(includingIgnoredKeywords(filter)).isSameAs(filter);
    }

    @Test
    void shouldKeepParameterOrderWithSeveralColumnsWithKeywordSubfield() {
        var filter =
                SourceTableUtils.call(
                        BuiltInFunctionDefinitions.AND,
                        SourceTableUtils.call(
                                BuiltInFunctionDefinitions.EQUALS, RAW_XML, SourceTableUtils.literal("first")),
                        SourceTableUtils.call(
                                BuiltInFunctionDefinitions.GREATER_THAN, CAPITAL_NAME, SourceTableUtils.literal("second")));

        var predicate = SourceTableUtils.translate(includingIgnoredKeywords(filter));

        assertThat(predicate.getPredicate())
                .isEqualTo("""
                        ((("RawXml" = ?) AND ("Name" > ?)) OR (%s OR %s))"""
                        .formatted(RAW_XML_IGNORED, CAPITAL_NAME_IGNORED));
        assertThat(predicate.getParameters()).containsExactly("first", "second");
    }

    @Test
    void shouldIncludeIgnoredKeywordsOfBothComparedColumns() {
        var predicate = SourceTableUtils.translate(
                includingIgnoredKeywords(SourceTableUtils.call(BuiltInFunctionDefinitions.EQUALS, RAW_XML, CAPITAL_NAME)));

        assertThat(predicate.getPredicate())
                .isEqualTo("""
                        (("RawXml" = "Name") OR (%s OR %s))""".formatted(RAW_XML_IGNORED, CAPITAL_NAME_IGNORED));
        assertThat(predicate.getParameters()).isEmpty();
    }

    @Test
    void shouldIncludeIgnoredKeywordsOnceForRepeatedColumn() {
        var filter =
                SourceTableUtils.call(
                        BuiltInFunctionDefinitions.OR,
                        SourceTableUtils.equalsFilter("RawXml", 0, "a"),
                        SourceTableUtils.equalsFilter("RawXml", 0, "b"));

        var predicate = SourceTableUtils.translate(includingIgnoredKeywords(filter));

        assertThat(predicate.getPredicate())
                .isEqualTo("""
                        ((("RawXml" = ?) OR ("RawXml" = ?)) OR %s)""".formatted(RAW_XML_IGNORED));
    }

    @Test
    void shouldIncludeIgnoredKeywordsInComparisonWithOtherColumn() {
        var predicate = SourceTableUtils.translate(
                includingIgnoredKeywords(SourceTableUtils.call(BuiltInFunctionDefinitions.EQUALS, KW, RAW_XML)));

        assertThat(predicate.getPredicate())
                .isEqualTo("((\"kw\" = \"RawXml\") OR %s)".formatted(RAW_XML_IGNORED));
    }

    @Test
    void shouldQuoteSubfieldsWithSpecialCharacters() {
        assertThat(predicateOfEqualsIncludingIgnoredKeywords("geo:loc"))
                .isEqualTo("""
                        (("geo:loc" = ?) OR (("geo:loc.keyword" IS NULL) AND ("geo:loc" IS NOT NULL)))""");
        assertThat(predicateOfEqualsIncludingIgnoredKeywords("a\"b"))
                .isEqualTo("""
                        (("a""b" = ?) OR (("a""b.keyword" IS NULL) AND ("a""b" IS NOT NULL)))""");
    }

    @Test
    void shouldNotIncludeIgnoredKeywordsOfColumnThatIsNotAString() {
        var filter =
                SourceTableUtils.call(
                        BuiltInFunctionDefinitions.EQUALS,
                        SourceTableUtils.field("num", DataTypes.BIGINT(), 0),
                        SourceTableUtils.literal(1L));

        assertThat(includingIgnoredKeywords(filter)).isSameAs(filter);
    }

    @Test
    void shouldIncludeIgnoredKeywordsAlsoInCallsThatAreNotPushedDown() {
        var notCondition =
                SourceTableUtils.call(
                        BuiltInFunctionDefinitions.NOT, SourceTableUtils.equalsFilter("RawXml", 0, "x"));
        var expectedCondition =
                SourceTableUtils.call(
                        BuiltInFunctionDefinitions.AND,
                        SourceTableUtils.call(
                                BuiltInFunctionDefinitions.IS_NULL, SourceTableUtils.stringField("RawXml.keyword", 0)),
                        SourceTableUtils.call(BuiltInFunctionDefinitions.IS_NOT_NULL, RAW_XML));

        var rechecked = includingIgnoredKeywords(notCondition);

        assertThat(rechecked)
                .isEqualTo(SourceTableUtils.call(BuiltInFunctionDefinitions.OR, notCondition, expectedCondition));
        assertThat(SourceTableUtils.translateIfSupported(rechecked)).isEmpty();
    }

    @Test
    void shouldNotIncludeIgnoredKeywordsInExpressionsThatAreNotCalls() {
        var field = SourceTableUtils.field("flag", DataTypes.BOOLEAN(), 0);

        assertThat(includingIgnoredKeywords(field)).isSameAs(field);
    }

    @Test
    void shouldNotReadKeywordSubfieldsWithoutConditionOnStringColumn() {
        // reading from the unreachable Elasticsearch would fail
        var unreadable = KeywordSubfields.read(SourceTableUtils.unreachableOptions());
        var filter =
                SourceTableUtils.call(
                        BuiltInFunctionDefinitions.AND,
                        SourceTableUtils.call(BuiltInFunctionDefinitions.EQUALS, ID, SourceTableUtils.literal(5L)),
                        SourceTableUtils.call(BuiltInFunctionDefinitions.IS_NULL, RAW_XML));

        assertThat(RecheckedFilters.includingIgnoredKeywords(filter, unreadable)).isSameAs(filter);
    }

    @Test
    void shouldStayUntranslatableWhenConditionIsUntranslatable() {
        var filter =
                SourceTableUtils.call(
                        BuiltInFunctionDefinitions.AND,
                        SourceTableUtils.upperEquals("RawXml", 0, "X"),
                        SourceTableUtils.equalsFilter("RawXml", 0, "x"));

        assertThat(SourceTableUtils.translateIfSupported(includingIgnoredKeywords(filter))).isEmpty();
    }

    @Test
    void shouldNotChangeAnythingWithoutColumnsWithKeywordSubfield() {
        var filter = SourceTableUtils.call(BuiltInFunctionDefinitions.LIKE, RAW_XML, SourceTableUtils.literal("%a%"));

        assertThat(RecheckedFilters.includingIgnoredKeywords(filter, KeywordSubfields.NONE)).isSameAs(filter);
    }

    @Test
    void shouldRecheckOnlyFiltersOnColumnsWithKeywordSubfield() {
        var onRecheckedColumn = SourceTableUtils.equalsFilter(NAME, 1, "x");
        var onOtherColumn = SourceTableUtils.equalsFilter(REFERENCE_ID, 0, "y");

        var recheckedFilters = RecheckedFilters.of(List.of(onRecheckedColumn, onOtherColumn), NAME_KEYWORD_SUBFIELD);

        assertThat(recheckedFilters.filters()).hasSize(2);
        assertThat(recheckedFilters.filters().get(0)).isNotSameAs(onRecheckedColumn);
        assertThat(recheckedFilters.filters().get(1)).isSameAs(onOtherColumn);
        assertThat(recheckedFilters.originalOf(recheckedFilters.filters().get(0))).isSameAs(onRecheckedColumn);
    }

    @Test
    void shouldReturnOriginalFiltersAndRecheckedAcceptedAlsoAsRemaining() {
        var onRecheckedColumn = SourceTableUtils.equalsFilter(NAME, 1, "x");
        var onOtherColumn = SourceTableUtils.equalsFilter(REFERENCE_ID, 0, "y");
        var rejected = SourceTableUtils.equalsFilter(REFERENCE_ID, 0, "z");
        var recheckedFilters =
                RecheckedFilters.of(List.of(onRecheckedColumn, onOtherColumn, rejected), NAME_KEYWORD_SUBFIELD);
        var rechecked = recheckedFilters.filters();

        var result = recheckedFilters.toResult(
                Result.of(List.of(rechecked.get(0), rechecked.get(1)), List.of(rechecked.get(2))));

        assertThat(result.getAcceptedFilters()).containsExactly(onRecheckedColumn, onOtherColumn);
        assertThat(result.getRemainingFilters()).containsExactly(rejected, onRecheckedColumn);
        assertThat(result.getAcceptedFilters().get(0)).isSameAs(onRecheckedColumn);
        assertThat(result.getRemainingFilters().get(1)).isSameAs(onRecheckedColumn);
    }

    private String predicateOfEqualsIncludingIgnoredKeywords(String column) {
        return SourceTableUtils.translate(includingIgnoredKeywords(SourceTableUtils.equalsFilter(column, 0, "x")))
                .getPredicate();
    }

    private static ResolvedExpression includingIgnoredKeywords(ResolvedExpression filter) {
        return RecheckedFilters.includingIgnoredKeywords(filter, KEYWORD_SUBFIELDS);
    }
}
