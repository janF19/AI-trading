package com.trading.validation;

import com.trading.pricing.AlphaVantagePriceProvider;
import com.trading.pricing.PriceDataProvider;
import com.trading.pricing.PriceDataProvider.PricePoint;
import com.trading.pricing.TwelveDataPriceProvider;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Service that validates sentiment predictions against actual price movements.
 * Waits 48 hours before validating to ensure daily price data is available.
 */
@Singleton
public class PriceValidationService {
    private static final Logger LOG = LoggerFactory.getLogger(PriceValidationService.class);
    private static final ZoneId ET_ZONE = ZoneId.of("America/New_York");
    private static final int MARKET_OPEN_HOUR = 9; // 9:30 AM ET
    private static final int MARKET_CLOSE_HOUR = 16; // 4:00 PM ET
    private static final int MIN_AGE_HOURS = 48; // Wait 48h for daily price data availability

    private final DataSource dataSource;
    private final TwelveDataPriceProvider twelveDataProvider;
    private final AlphaVantagePriceProvider alphaVantageProvider;

    @Inject
    public PriceValidationService(
            DataSource dataSource,
            TwelveDataPriceProvider twelveDataProvider,
            AlphaVantagePriceProvider alphaVantageProvider) {
        this.dataSource = dataSource;
        this.twelveDataProvider = twelveDataProvider;
        this.alphaVantageProvider = alphaVantageProvider;
        LOG.info("PriceValidationService initialized - validates news 48+ hours old (TwelveData + AlphaVantage fallback)");
    }

    record SentimentRecord(
        String articleId,
        String ticker,
        double sentimentScore,
        Timestamp newsTimestamp
    ) {}

    /**
     * Runs every 6 hours to validate sentiments that are now 48+ hours old.
     */
    @Scheduled(fixedDelay = "6h", initialDelay = "30s") // 30s initial delay for testing
    public void validatePendingSentiments() {
        LOG.info("==> Price validation scheduled task FIRED (processing news 48+ hours old)");

        try {
            List<SentimentRecord> pending = fetchPendingSentiments();
            LOG.info("Fetched {} pending sentiments for validation (48+ hours old)", pending.size());
            if (pending.isEmpty()) {
                LOG.info("No pending sentiments older than 48h to validate");
                return;
            }

            LOG.info("Validating {} sentiments using daily price data", pending.size());
            List<SentimentRecord> sorted = pending.stream()
                .sorted(Comparator.comparingDouble((SentimentRecord s) -> Math.abs(s.sentimentScore())).reversed())
                .toList();

            int validated = 0;
            int apiCallCount = 0;
            for (SentimentRecord record : sorted) {
                if (apiCallCount > 0 && apiCallCount % 4 == 0) {
                    LOG.info("Rate limit: Processed {} sentiments, sleeping 60s", apiCallCount);
                    Thread.sleep(60000);
                }

                boolean success = validateSentiment(record);
                if (success) {
                    validated++;
                }
                apiCallCount++;
            }

            LOG.info("Successfully validated {}/{} sentiments", validated, pending.size());

        } catch (Exception e) {
            LOG.error("Failed to validate pending sentiments", e);
        }
    }

    /**
     * Fetches unvalidated sentiments from news older than 48 hours.
     */
    private List<SentimentRecord> fetchPendingSentiments() {
        String sql = """
            SELECT article_id, ticker, sentiment, news_timestamp
            FROM news_analyzed
            WHERE validated = false
              AND news_timestamp < now() - INTERVAL 48 HOUR
            ORDER BY abs(sentiment) DESC
            LIMIT 50
            """;

        List<SentimentRecord> records = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ResultSet rs = ps.executeQuery();

            while (rs.next()) {
                records.add(new SentimentRecord(
                    rs.getString("article_id"),
                    rs.getString("ticker"),
                    rs.getDouble("sentiment"),
                    rs.getTimestamp("news_timestamp")
                ));
            }

            LOG.debug("Found {} pending sentiments older than 48h", records.size());
        } catch (Exception e) {
            LOG.error("Failed to fetch pending sentiments", e);
        }
        return records;
    }

    /**
     * Validates a single sentiment by comparing closing prices before and after news.
     */
    private boolean validateSentiment(SentimentRecord record) {
        try {
            Instant newsTime = record.newsTimestamp().toInstant();
            ZonedDateTime newsET = newsTime.atZone(ET_ZONE);

            boolean duringMarketHours = isMarketHoursForNews(newsET);
            LocalDate referenceDay = duringMarketHours ?
                newsET.toLocalDate() :
                newsET.toLocalDate().minusDays(1);
            LocalDate nextDay = newsET.toLocalDate().plusDays(1);

            Instant refDayClose = referenceDay.atTime(16, 0).atZone(ET_ZONE).toInstant();
            Instant nextDayClose = nextDay.atTime(16, 0).atZone(ET_ZONE).toInstant();
            Optional<PricePoint> priceAtT = getPriceWithFallback(record.ticker(), refDayClose);
            Optional<PricePoint> priceAtT1d = getPriceWithFallback(record.ticker(), nextDayClose);

            if (priceAtT.isEmpty() || priceAtT1d.isEmpty()) {
                LOG.warn("Could not fetch prices for ticker: {} at T={}", record.ticker(), newsTime);
                return false;
            }

            PricePoint pT = priceAtT.get();
            PricePoint pT1d = priceAtT1d.get();

            double priceDiff = pT1d.close() - pT.close();
            double priceChangePct = (priceDiff / pT.close()) * 100.0;
            int actualDirection = priceDiff > 0 ? 1 : (priceDiff < 0 ? -1 : 0);
            int sentimentDirection = record.sentimentScore() > 0 ? 1 : (record.sentimentScore() < 0 ? -1 : 0);
            boolean predictionCorrect = (actualDirection == sentimentDirection) && (actualDirection != 0);

            String provider = "TwelveData";
            saveValidationResult(
                record.articleId(),
                record.ticker(),
                pT.close(),
                pT1d.close(),
                priceDiff,
                priceChangePct,
                record.sentimentScore(),
                actualDirection,
                predictionCorrect,
                record.newsTimestamp(),
                provider
            );

            // Mark as validated
            markAsValidated(record.articleId(), record.ticker());

            LOG.info("Validated {}: sentiment={}, price {}→{} ({}%), correct={}",
                record.ticker(), record.sentimentScore(), pT.close(), pT1d.close(),
                String.format("%.2f", priceChangePct), predictionCorrect);

            return true;

        } catch (Exception e) {
            LOG.error("Failed to validate sentiment for ticker: {}", record.ticker(), e);
            return false;
        }
    }

    /**
     * Attempts to fetch price from TwelveData, falls back to AlphaVantage on failure.
     */
    private Optional<PricePoint> getPriceWithFallback(String ticker, Instant timestamp) {
        Optional<PricePoint> price = twelveDataProvider.getPriceAt(ticker, timestamp);
        if (price.isPresent()) {
            return price;
        }

        LOG.debug("TwelveData failed for {}, trying AlphaVantage fallback", ticker);
        return alphaVantageProvider.getPriceAt(ticker, timestamp);
    }

    private void saveValidationResult(
            String articleId,
            String ticker,
            double priceAtT,
            double priceAtT1h,
            double priceDiff,
            double priceChangePct,
            double sentimentScore,
            int actualDirection,
            boolean predictionCorrect,
            Timestamp newsTimestamp,
            String provider) {

        String sql = """
            INSERT INTO signals_verified (
                news_sentiment_id, ticker, price_at_t, price_at_t_plus_1h,
                price_diff, price_change_pct, sentiment_score, actual_direction,
                prediction_correct, news_timestamp, validated_at, provider
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, articleId);
            ps.setString(2, ticker);
            ps.setDouble(3, priceAtT);
            ps.setDouble(4, priceAtT1h);
            ps.setDouble(5, priceDiff);
            ps.setDouble(6, priceChangePct);
            ps.setDouble(7, sentimentScore);
            ps.setInt(8, actualDirection);
            ps.setBoolean(9, predictionCorrect);
            ps.setTimestamp(10, newsTimestamp);
            ps.setTimestamp(11, new Timestamp(System.currentTimeMillis()));
            ps.setString(12, provider);

            ps.executeUpdate();
            LOG.debug("Saved validation result for {}", ticker);

        } catch (Exception e) {
            LOG.error("Failed to save validation result", e);
        }
    }

    private void markAsValidated(String articleId, String ticker) {
        String sql = """
            ALTER TABLE news_analyzed
            UPDATE validated = true
            WHERE article_id = ? AND ticker = ?
            """;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, articleId);
            ps.setString(2, ticker);
            ps.executeUpdate();

        } catch (Exception e) {
            LOG.error("Failed to mark as validated", e);
        }
    }

    private boolean isMarketHoursForNews(ZonedDateTime newsTimeET) {
        int dayOfWeek = newsTimeET.getDayOfWeek().getValue();
        int hour = newsTimeET.getHour();
        int minute = newsTimeET.getMinute();

        if (dayOfWeek == 6 || dayOfWeek == 7) {
            return false;
        }

        if (hour < MARKET_OPEN_HOUR || hour >= MARKET_CLOSE_HOUR) {
            return false;
        }

        if (hour == MARKET_OPEN_HOUR && minute < 30) {
            return false;
        }

        return true;
    }

}
