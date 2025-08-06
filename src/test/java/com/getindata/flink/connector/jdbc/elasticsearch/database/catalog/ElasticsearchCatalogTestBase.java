package com.getindata.flink.connector.jdbc.elasticsearch.database.catalog;

import com.getindata.flink.connector.jdbc.elasticsearch.ElasticsearchTestBase;
import okhttp3.Credentials;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.commons.compress.utils.IOUtils;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static org.junit.Assert.assertTrue;

public class ElasticsearchCatalogTestBase implements ElasticsearchTestBase {

    protected static ElasticsearchJdbcCatalogFactory catalogFactory = new ElasticsearchJdbcCatalogFactory();

    protected Map<String, String> getCommonOptions() {
        Map<String, String> options = new HashMap<>();
        options.put("base-url", getMetadata().getJdbcUrl());
        options.put("default-database", "test-database");
        options.put("password", getMetadata().getPassword());
        options.put("username", getMetadata().getUsername());
        return options;
    }

    protected String getHttpUrl() {
        System.out.println(getMetadata().getJdbcUrl().replace("jdbc:elasticsearch", "http"));
        return getMetadata().getJdbcUrl().replace("jdbc:elasticsearch", "http");
    }

    protected void createTestIndex(String inputTable, String indexPath) throws Exception {
        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder()
                .url(String.format("%s/%s/", getHttpUrl(), inputTable))
                .put(RequestBody.create(loadResource(indexPath)))
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", Credentials.basic(getMetadata().getUsername(), getMetadata().getPassword()))
                .build();
        try (Response response = client.newCall(request).execute()) {
            assertTrue(response.isSuccessful());
        }
    }

    protected void deleteTestIndex(String inputTable) throws Exception {
        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder()
                .url(String.format("%s/%s/", getHttpUrl(), inputTable))
                .delete()
                .addHeader("Authorization", Credentials.basic(getMetadata().getUsername(), getMetadata().getPassword()))
                .build();
        try (Response response = client.newCall(request).execute()) {
            assertTrue(response.isSuccessful());
        }
    }

    protected void deleteAll() throws IOException {
        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder()
                .url(String.format("%s/_all", getHttpUrl()))
                .delete()
                .addHeader("Authorization", Credentials.basic(getMetadata().getUsername(), getMetadata().getPassword()))
                .build();
        try (Response response = client.newCall(request).execute()) {
            assertTrue(response.isSuccessful());
        }
    }

    protected void addTestData(String inputTable, String inputPath) throws Exception {
        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder()
                .url(String.format("%s/%s/_bulk/", getHttpUrl(), inputTable))
                .post(RequestBody.create(loadResource(inputPath)))
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", Credentials.basic(getMetadata().getUsername(), getMetadata().getPassword()))
                .build();
        client.newCall(request).execute();
    }

    protected static byte[] loadResource(String path) throws IOException {
        return IOUtils.toByteArray(
                Objects.requireNonNull(ElasticsearchCatalogTestBase.class.getClassLoader().getResourceAsStream(path))
        );
    }

    protected static String calculateExpectedTemporalLowerBound() {
        // upper bound for temporal partition columns is the last millisecond of the current day
        LocalDate todayDate = LocalDate.now();
        return String.valueOf(todayDate.atTime(LocalTime.MIN).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
    }

    protected static String calculateExpectedTemporalUpperBound() {
        // upper bound for temporal partition columns is the last millisecond of the current day
        LocalDate tomorrowDate = LocalDate.now().plusDays(1);
        return String.valueOf(tomorrowDate.atTime(LocalTime.MIDNIGHT).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1);
    }

}
