package com.trading.ai;

import com.trading.ai.GeminiService.NewsArticle;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Singleton
public class NewsAnalysisService {

    @PostConstruct
    void onInit() {
        LOG.info("NewsAnalysisService @PostConstruct - scheduling enabled");
    }
    private static final Logger LOG = LoggerFactory.getLogger(NewsAnalysisService.class);
    private static final int BATCH_SIZE = 20;
    private final GeminiService geminiService;
    private final DataSource dataSource;

    @Inject
    public NewsAnalysisService(GeminiService geminiService, DataSource dataSource) {
        this.geminiService = geminiService;
        this.dataSource = dataSource;
        LOG.info("NewsAnalysisService initialized");
    }

    @Scheduled(fixedDelay = "15m", initialDelay = "10s")
    public void processPendingNews() {
        LOG.info("==> Sentiment analysis scheduled task FIRED");
        try {
            List<NewsWithTimestamp> pending = fetchPendingNews();
            LOG.info("Found {} pending articles to analyze", pending.size());
            if (pending.isEmpty()) {
                LOG.info("No pending articles, skipping this run");
                return;
            }

            LOG.info("Processing {} pending articles", pending.size());

            List<NewsArticle> articles = pending.stream()
                .map(n -> new NewsArticle(n.id(), n.title(), n.summary()))
                .toList();

            Map<String, SentimentResult> results = geminiService.analyzeBatch(articles);

            for (Map.Entry<String, SentimentResult> entry : results.entrySet()) {
                String articleId = entry.getKey();
                SentimentResult result = entry.getValue();

                LOG.info("Analysis for {}: relevant={}, tickers={}, sentiment={}",
                    articleId, result.marketRelevant(), result.tickers(), result.sentiment());

                // Only save if market relevant AND has exactly 1 ticker
                if (result.marketRelevant() && result.tickers().size() == 1) {
                    // Find original news timestamp
                    NewsWithTimestamp news = pending.stream()
                        .filter(n -> n.id().equals(articleId))
                        .findFirst()
                        .orElse(null);

                    if (news != null) {
                        saveAnalysis(articleId, result, news.timestamp());
                    }
                } else if (result.marketRelevant() && result.tickers().size() != 1) {
                    LOG.debug("Skipping {}: expected 1 ticker, got {}", articleId, result.tickers().size());
                }
            }
        } catch (Exception e) {
            LOG.error("Batch processing failed", e);
        }
    }

    record NewsWithTimestamp(String id, String title, String summary, Timestamp timestamp) {}

    private List<NewsWithTimestamp> fetchPendingNews() {
        String sql = """
            SELECT id, title, JSONExtractString(data, 'summary') as summary, timestamp
            FROM news_raw
            WHERE id NOT IN (SELECT article_id FROM news_analyzed)
            ORDER BY timestamp ASC
            LIMIT ?
            """;

        List<NewsWithTimestamp> articles = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, BATCH_SIZE);
            ResultSet rs = ps.executeQuery();

            while (rs.next()) {
                articles.add(new NewsWithTimestamp(
                    rs.getString("id"),
                    rs.getString("title"),
                    rs.getString("summary"),
                    rs.getTimestamp("timestamp")
                ));
            }
        } catch (Exception e) {
            LOG.error("Failed to fetch pending news", e);
        }
        return articles;
    }

    private void saveAnalysis(String articleId, SentimentResult result, Timestamp newsTimestamp) {
        String sql = """
            INSERT INTO news_analyzed (article_id, ticker, sentiment, reasoning, analyzed_at, news_timestamp, validated)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            for (String ticker : result.tickers()) {
                ps.setString(1, articleId);
                ps.setString(2, ticker);
                ps.setDouble(3, result.sentiment());
                ps.setString(4, result.reasoning());
                ps.setTimestamp(5, new Timestamp(System.currentTimeMillis()));
                ps.setTimestamp(6, newsTimestamp);
                ps.setBoolean(7, false); // Not yet validated
                ps.addBatch();
            }

            ps.executeBatch();
            LOG.info("Saved analysis for {} tickers", result.tickers().size());
        } catch (Exception e) {
            LOG.error("Failed to save analysis", e);
        }
    }
}
