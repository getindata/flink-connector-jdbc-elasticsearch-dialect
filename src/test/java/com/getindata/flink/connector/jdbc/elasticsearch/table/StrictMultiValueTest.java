package com.getindata.flink.connector.jdbc.elasticsearch.table;

import org.apache.flink.table.api.ValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StrictMultiValueTest {

    private static final String URL = "jdbc:es://localhost:9200";
    private static final String LENIENT_REJECTED = """
            'field.multi.value.leniency' = 'true' cannot be used with 'filter.ignored-keyword.handling' = 'recheck', \
            remove it or set 'filter.ignored-keyword.handling' = 'none'.""";

    @ParameterizedTest
    @ValueSource(strings = {
            URL,
            URL + "/",
            URL + "/?",
            URL + "/db",
            URL + "/?a=b&",
            URL + "/?a=%zz",
            "jdbc:elasticsearch://localhost:9200/?ssl=true",
            URL + "/?field.multi.value.leniency=false",
            URL + "/?ssl=false&field.multi.value.leniency=FALSE",
            URL + "/db?field.multi.value.leniency=false",
            URL + "/?field.multi.value.leniency=no",
            URL + "/?field.multi.value.leniency=true&field.multi.value.leniency=false",
            "jdbc:elasticsearch://localhost:9200/?field.multi.value.leniency=false"
    })
    void shouldSetStrictLeniency(String url) {
        assertThat(enforce(url)).containsEntry(StrictMultiValue.LENIENCY, "false");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            URL + "/?field.multi.value.leniency=true",
            URL + "/?ssl=false&field.multi.value.leniency=True",
            URL + "/?field.multi.value.leniency=false&field.multi.value.leniency=TRUE",
            URL + "/?field.multi.value.leniency=%74rue",
            "jdbc:elasticsearch://localhost:9200/db?field.multi.value.leniency=true"
    })
    void shouldRejectLenientUrl(String url) {
        assertThatThrownBy(() -> enforce(url))
                .isInstanceOf(ValidationException.class)
                .hasMessage(LENIENT_REJECTED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "True"})
    void shouldRejectLenientProperty(String value) {
        assertThatThrownBy(() -> StrictMultiValue.enforce(URL, leniency(value)))
                .isInstanceOf(ValidationException.class)
                .hasMessage(LENIENT_REJECTED);
    }

    @Test
    void shouldAcceptStrictProperty() {
        assertThat(StrictMultiValue.enforce(URL, leniency("false")))
                .containsEntry(StrictMultiValue.LENIENCY, "false");
    }

    @Test
    void connectionPropertiesShouldOverrideUrl() {
        assertThat(StrictMultiValue.enforce(URL + "/?field.multi.value.leniency=true", leniency("no")))
                .containsEntry(StrictMultiValue.LENIENCY, "false");
        assertThatThrownBy(() -> StrictMultiValue.enforce(URL + "/?field.multi.value.leniency=false",
                leniency("true")))
                .isInstanceOf(ValidationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            URL + "/?field.multi.value.leniency",
            URL + "/?field.multi.value.leniency=%zz",
            URL + "/?field.multi.value.leniency.x=true",
            URL + "/?a=b&field.multi.value.leniency=false",
            "http://localhost:9200/?field.multi.value.leniency=false"
    })
    void shouldRejectInvalidConfiguration(String url) {
        assertThatThrownBy(() -> enforce(url))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Cannot read 'field.multi.value.leniency'");
    }

    @Test
    void shouldKeepOtherPropertiesWithoutModifyingGivenOnes() {
        var given = new Properties();
        given.setProperty("ssl", "true");

        var strict = StrictMultiValue.enforce(URL, given);

        assertThat(strict).containsEntry("ssl", "true").containsEntry(StrictMultiValue.LENIENCY, "false");
        assertThat(given).doesNotContainKey(StrictMultiValue.LENIENCY);
    }

    @Test
    void shouldNotModifyGivenLeniency() {
        var given = leniency("no");

        assertThat(StrictMultiValue.enforce(URL, given)).containsEntry(StrictMultiValue.LENIENCY, "false");
        assertThat(given).containsEntry(StrictMultiValue.LENIENCY, "no");
    }

    private static Properties enforce(String url) {
        return StrictMultiValue.enforce(url, new Properties());
    }

    private static Properties leniency(String value) {
        var properties = new Properties();
        properties.setProperty(StrictMultiValue.LENIENCY, value);
        return properties;
    }
}
