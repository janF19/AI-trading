package com.trading.ingestion;

import io.micronaut.configuration.kafka.annotation.KafkaListener;
import io.micronaut.configuration.kafka.annotation.Topic;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;

@KafkaListener(groupId = "news-archiver")
public class NewsConsumer {
    private static final Logger LOG = LoggerFactory.getLogger(NewsConsumer.class);
    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    @Inject
    public NewsConsumer(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
    }

    @Topic("news.live")
    public void receive(@io.micronaut.configuration.kafka.annotation.KafkaKey String id, String newsJson) {
        LOG.info("Received news batch for archival: {}", id);
        try (Connection conn = dataSource.getConnection()) {
            JsonNode root = objectMapper.readTree(newsJson);

            if (root.isArray()) {
                for (JsonNode article : root) {
                    saveArticle(conn, article);
                }
                LOG.info("Saved {} articles to ClickHouse", root.size());
            } else {
                saveArticle(conn, root);
                LOG.info("Saved 1 article to ClickHouse");
            }
        } catch (Exception e) {
            LOG.error("Error saving news to ClickHouse", e);
        }
    }

    private void saveArticle(Connection conn, JsonNode article) throws Exception {
        String articleId = article.has("id") ? article.get("id").asText() : String.valueOf(article.hashCode());

        // Check if article already exists to avoid unnecessary writes
        try (PreparedStatement checkPs = conn.prepareStatement(
                "SELECT count() FROM news_raw WHERE id = ?")) {
            checkPs.setString(1, articleId);
            var rs = checkPs.executeQuery();
            if (rs.next() && rs.getInt(1) > 0) {
                LOG.debug("Article {} already exists, skipping", articleId);
                return;
            }
        }

        // Insert new article
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO news_raw (id, title, timestamp, source, data) VALUES (?, ?, ?, ?, ?)")) {

            String title = article.has("headline") ? article.get("headline").asText() : article.path("title").asText("Unknown Title");

            Timestamp ts;
            if (article.has("datetime")) {
                long epochSeconds = article.get("datetime").asLong();
                ts = Timestamp.from(Instant.ofEpochSecond(epochSeconds));
            } else {
                String tsStr = article.path("timestamp").asText();
                ts = tsStr.isEmpty() ? Timestamp.from(Instant.now()) : Timestamp.from(Instant.parse(tsStr));
            }

            String source = article.path("source").asText("Unknown Source");

            ps.setString(1, articleId);
            ps.setString(2, title);
            ps.setTimestamp(3, ts);
            ps.setString(4, source);
            ps.setString(5, article.toString());

            ps.executeUpdate();
            LOG.debug("Saved article {} - will be picked up by batch analyzer", articleId);
        }
    }
}
