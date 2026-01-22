package com.trading.config;

import io.micronaut.context.event.StartupEvent;
import io.micronaut.runtime.event.annotation.EventListener;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

@Singleton
public class DatabaseInitializer {
    private static final Logger LOG = LoggerFactory.getLogger(DatabaseInitializer.class);
    private final DataSource dataSource;

    public DatabaseInitializer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @EventListener
    public void onStartup(StartupEvent event) {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            String sql = """
                CREATE TABLE IF NOT EXISTS news_raw (
                    id String,
                    title String,
                    timestamp DateTime,
                    source String,
                    data String
                ) ENGINE = ReplacingMergeTree()
                ORDER BY id
                SETTINGS index_granularity = 8192
            """;

            stmt.execute(sql);
            LOG.info("ClickHouse table 'news_raw' initialized.");

            String analysisSql = """
                CREATE TABLE IF NOT EXISTS news_analyzed (
                    article_id String,
                    ticker String,
                    sentiment Float64,
                    reasoning String,
                    analyzed_at DateTime,
                    news_timestamp DateTime,
                    validated Boolean DEFAULT false
                ) ENGINE = MergeTree()
                ORDER BY (ticker, analyzed_at)
                SETTINGS index_granularity = 8192
            """;

            stmt.execute(analysisSql);
            LOG.info("ClickHouse table 'news_analyzed' initialized.");

            String signalsSql = """
                CREATE TABLE IF NOT EXISTS signals_verified (
                    id UUID DEFAULT generateUUIDv4(),
                    news_sentiment_id String,
                    ticker String,
                    price_at_t Float64,
                    price_at_t_plus_1h Float64,
                    price_diff Float64,
                    price_change_pct Float64,
                    sentiment_score Float64,
                    actual_direction Int8,
                    prediction_correct Boolean,
                    news_timestamp DateTime,
                    validated_at DateTime,
                    provider String
                ) ENGINE = MergeTree()
                ORDER BY (ticker, news_timestamp)
                SETTINGS index_granularity = 8192
            """;

            stmt.execute(signalsSql);
            LOG.info("ClickHouse table 'signals_verified' initialized.");
        } catch (Exception e) {
            LOG.error("Failed to initialize ClickHouse database", e);
        }
    }
}
