package com.getindata.flink.connector.jdbc.elasticsearch.database.catalog;

import java.util.Map;

public class ElasticsearchCatalogBuilder {
    private ClassLoader userClassLoader;
    private String username;
    private String password;
    private String baseUrl;
    private boolean addProctimeColumn;
    private IndexFilterResolver indexFilterResolver;
    private String catalogName;
    private String defaultDatabase;

    private Map<String, String> properties;

    private ElasticsearchCatalogBuilder() {
    }

    public static ElasticsearchCatalogBuilder anElasticCatalog() {
        return new ElasticsearchCatalogBuilder();
    }

    public ElasticsearchCatalogBuilder userClassLoader(ClassLoader userClassLoader) {
        this.userClassLoader = userClassLoader;
        return this;
    }

    public ElasticsearchCatalogBuilder username(String username) {
        this.username = username;
        return this;
    }

    public ElasticsearchCatalogBuilder password(String password) {
        this.password = password;
        return this;
    }

    public ElasticsearchCatalogBuilder baseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
        return this;
    }

    public ElasticsearchCatalogBuilder addProctimeColumn(boolean addProctimeColumn) {
        this.addProctimeColumn = addProctimeColumn;
        return this;
    }

    public ElasticsearchCatalogBuilder indexFilterResolver(IndexFilterResolver indexFilterResolver) {
        this.indexFilterResolver = indexFilterResolver;
        return this;
    }

    public ElasticsearchCatalogBuilder catalogName(String catalogName) {
        this.catalogName = catalogName;
        return this;
    }

    public ElasticsearchCatalogBuilder defaultDatabase(String defaultDatabase) {
        this.defaultDatabase = defaultDatabase;
        return this;
    }


    public ElasticsearchCatalogBuilder properties(Map<String, String> properties) {
        this.properties = properties;
        return this;
    }

    public ElasticsearchCatalog build() {
        return new ElasticsearchCatalog(userClassLoader, catalogName, defaultDatabase, username, password, baseUrl,
                addProctimeColumn, indexFilterResolver, properties);
    }
}
