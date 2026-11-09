package com.getindata.flink.connector.jdbc.elasticsearch.table;

import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.expressions.FieldReferenceExpression;
import org.apache.flink.table.functions.BuiltInFunctionDefinition;
import org.apache.flink.table.functions.BuiltInFunctionDefinitions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class FilterExtenderTest {

    private static final Map<String, String> RECHECK_COLUMNS =
            Map.of(
                    "RawXml", "RawXml.keyword",
                    "Name", "Name.raw",
                    "geo:loc", "geo:loc.keyword",
                    "a\"b", "a\"b.keyword",
                    // in the map, but declared as BIGINT in Flink
                    "num", "num.keyword");

    private static final String RAW_XML_IGNORED = """
            (("RawXml.keyword" IS NULL) AND ("RawXml" IS NOT NULL))""";
    private static final String NAME_IGNORED = """
            (("Name.raw" IS NULL) AND ("Name" IS NOT NULL))""";

    private static final FieldReferenceExpression RAW_XML = SourceTableUtils.stringField("RawXml", 0);
    private static final FieldReferenceExpression NAME = SourceTableUtils.stringField("Name", 0);
    private static final FieldReferenceExpression KW = SourceTableUtils.stringField("kw", 0);
    private static final FieldReferenceExpression ID = SourceTableUtils.field("id", DataTypes.BIGINT(), 0);

    private final FilterExtender extender = new FilterExtender(() -> RECHECK_COLUMNS);

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
    void shouldExtendConditionOnRecheckColumn(BuiltInFunctionDefinition function, String operator) {
        var predicate = SourceTableUtils.translate(
                extender.extend(SourceTableUtils.call(function, RAW_XML, SourceTableUtils.literal("x"))));

        assertThat(predicate.getPredicate())
                .isEqualTo("((\"RawXml\" %s ?) OR %s)".formatted(operator, RAW_XML_IGNORED));
        assertThat(predicate.getParameters()).containsExactly("x");
    }

    @ParameterizedTest
    @MethodSource("binaryOperators")
    void shouldNotChangeConditionOnOtherColumn(BuiltInFunctionDefinition function, String operator) {
        var filter = SourceTableUtils.call(function, KW, SourceTableUtils.literal("x"));

        assertThat(extender.extend(filter)).isSameAs(filter);
    }

    @Test
    void shouldNotExtendIsNullAndIsNotNull() {
        var isNull = SourceTableUtils.call(BuiltInFunctionDefinitions.IS_NULL, RAW_XML);
        var isNotNull = SourceTableUtils.call(BuiltInFunctionDefinitions.IS_NOT_NULL, RAW_XML);
        var both = SourceTableUtils.call(BuiltInFunctionDefinitions.OR, isNull, isNotNull);

        assertThat(extender.extend(isNull)).isSameAs(isNull);
        assertThat(extender.extend(isNotNull)).isSameAs(isNotNull);
        assertThat(extender.extend(both)).isSameAs(both);
    }

    @Test
    void shouldExtendOnlyConditionsOnRecheckColumnsInAndOr() {
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

        var predicate = SourceTableUtils.translate(extender.extend(filter));

        assertThat(predicate.getPredicate())
                .isEqualTo("""
                        (((("RawXml" LIKE ?) OR %s) AND ("id" = ?)) OR ("kw" = ?))"""
                        .formatted(RAW_XML_IGNORED));
        assertThat(predicate.getParameters()).containsExactly("%a%", 5L, "b");
    }

    @Test
    void shouldKeepAndOrWithoutRecheckColumnsUnchanged() {
        var filter =
                SourceTableUtils.call(
                        BuiltInFunctionDefinitions.OR,
                        SourceTableUtils.call(BuiltInFunctionDefinitions.LIKE, KW, SourceTableUtils.literal("%a%")),
                        SourceTableUtils.call(
                                BuiltInFunctionDefinitions.IS_NULL, SourceTableUtils.stringField("other", 0)));

        assertThat(extender.extend(filter)).isSameAs(filter);
    }

    @Test
    void shouldKeepParameterOrderWithSeveralExtendedConditions() {
        var filter =
                SourceTableUtils.call(
                        BuiltInFunctionDefinitions.AND,
                        SourceTableUtils.call(
                                BuiltInFunctionDefinitions.EQUALS, RAW_XML, SourceTableUtils.literal("first")),
                        SourceTableUtils.call(
                                BuiltInFunctionDefinitions.GREATER_THAN, NAME, SourceTableUtils.literal("second")));

        var predicate = SourceTableUtils.translate(extender.extend(filter));

        assertThat(predicate.getPredicate())
                .isEqualTo("""
                        ((("RawXml" = ?) OR %s) AND (("Name" > ?) OR %s))"""
                        .formatted(RAW_XML_IGNORED, NAME_IGNORED));
        assertThat(predicate.getParameters()).containsExactly("first", "second");
    }

    @Test
    void shouldExtendComparisonOfTwoColumnsWithSubfieldsOfBoth() {
        var predicate = SourceTableUtils.translate(
                extender.extend(SourceTableUtils.call(BuiltInFunctionDefinitions.EQUALS, RAW_XML, NAME)));

        assertThat(predicate.getPredicate())
                .isEqualTo("""
                        (("RawXml" = "Name") OR (%s OR %s))""".formatted(RAW_XML_IGNORED, NAME_IGNORED));
        assertThat(predicate.getParameters()).isEmpty();
    }

    @Test
    void shouldExtendComparisonWithOtherColumn() {
        var predicate = SourceTableUtils.translate(
                extender.extend(SourceTableUtils.call(BuiltInFunctionDefinitions.EQUALS, KW, RAW_XML)));

        assertThat(predicate.getPredicate())
                .isEqualTo("((\"kw\" = \"RawXml\") OR %s)".formatted(RAW_XML_IGNORED));
    }

    @Test
    void shouldQuoteSubfieldsWithSpecialCharacters() {
        assertThat(extendedPredicateOfEquals("geo:loc"))
                .isEqualTo("""
                        (("geo:loc" = ?) OR (("geo:loc.keyword" IS NULL) AND ("geo:loc" IS NOT NULL)))""");
        assertThat(extendedPredicateOfEquals("a\"b"))
                .isEqualTo("""
                        (("a""b" = ?) OR (("a""b.keyword" IS NULL) AND ("a""b" IS NOT NULL)))""");
    }

    @Test
    void shouldNotExtendConditionOnColumnThatIsNotAString() {
        var filter =
                SourceTableUtils.call(
                        BuiltInFunctionDefinitions.EQUALS,
                        SourceTableUtils.field("num", DataTypes.BIGINT(), 0),
                        SourceTableUtils.literal(1L));

        assertThat(extender.extend(filter)).isSameAs(filter);
    }

    @Test
    void shouldExtendOnRecheckColumnAlsoCallsThatAreNotPushedDown() {
        var notCondition =
                SourceTableUtils.call(
                        BuiltInFunctionDefinitions.NOT, SourceTableUtils.equalsFilter("RawXml", 0, "x"));
        var expectedMarker =
                SourceTableUtils.call(
                        BuiltInFunctionDefinitions.AND,
                        SourceTableUtils.call(
                                BuiltInFunctionDefinitions.IS_NULL, SourceTableUtils.stringField("RawXml.keyword", 0)),
                        SourceTableUtils.call(BuiltInFunctionDefinitions.IS_NOT_NULL, RAW_XML));

        var extended = extender.extend(notCondition);

        assertThat(extended)
                .isEqualTo(SourceTableUtils.call(BuiltInFunctionDefinitions.OR, notCondition, expectedMarker));
        assertThat(SourceTableUtils.translateIfSupported(extended)).isEmpty();
    }

    @Test
    void shouldNotExtendExpressionsThatAreNotCalls() {
        var field = SourceTableUtils.field("flag", DataTypes.BOOLEAN(), 0);

        assertThat(extender.extend(field)).isSameAs(field);
    }

    @Test
    void shouldNotReadRecheckColumnsWithoutConditionOnStringColumn() {
        var failingExtender = new FilterExtender(() -> {
            throw new AssertionError("recheck columns must not be read");
        });
        var filter =
                SourceTableUtils.call(
                        BuiltInFunctionDefinitions.AND,
                        SourceTableUtils.call(BuiltInFunctionDefinitions.EQUALS, ID, SourceTableUtils.literal(5L)),
                        SourceTableUtils.call(BuiltInFunctionDefinitions.IS_NULL, RAW_XML));

        assertThat(failingExtender.extend(filter)).isSameAs(filter);
    }

    @Test
    void shouldStayUntranslatableWhenConditionIsUntranslatable() {
        var filter =
                SourceTableUtils.call(
                        BuiltInFunctionDefinitions.AND,
                        SourceTableUtils.upperEquals("RawXml", 0, "X"),
                        SourceTableUtils.equalsFilter("RawXml", 0, "x"));

        assertThat(SourceTableUtils.translateIfSupported(extender.extend(filter))).isEmpty();
    }

    @Test
    void shouldNotChangeAnythingWithoutRecheckColumns() {
        var withoutColumns = new FilterExtender(Map::of);
        var filter = SourceTableUtils.call(BuiltInFunctionDefinitions.LIKE, RAW_XML, SourceTableUtils.literal("%a%"));

        assertThat(withoutColumns.extend(filter)).isSameAs(filter);
    }

    private String extendedPredicateOfEquals(String column) {
        return SourceTableUtils.translate(extender.extend(SourceTableUtils.equalsFilter(column, 0, "x")))
                .getPredicate();
    }
}
