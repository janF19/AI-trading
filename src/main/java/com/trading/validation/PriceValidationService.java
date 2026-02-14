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
 * Uses intraday data: compares price 5min before news vs 30min after news.
 * Includes volume analysis and S&P 500 benchmark comparison.
 */
@Singleton
public class PriceValidationService {
    private static final Logger LOG = LoggerFactory.getLogger(PriceValidationService.class);
    private static final ZoneId ET_ZONE = ZoneId.of("America/New_York");
    private static final int MARKET_OPEN_HOUR = 9; // 9:30 AM ET
    private static final int MARKET_CLOSE_HOUR = 16; // 4:00 PM ET
    private static final int MIN_AGE_HOURS = 2; // Wait 2h for intraday data availability

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
        LOG.info("PriceValidationService initialized - uses intraday data (5min before → 30min after news)");
    }

    record SentimentRecord(
        String articleId,
        String ticker,
        double sentimentScore,
        Timestamp newsTimestamp
    ) {}

    /**
     * Runs every 2 hours to validate sentiments that are now 2+ hours old.
     * Uses 2 API calls per stock (before + after price).
     * SPY benchmark disabled to conserve API calls.
     */
    @Scheduled(fixedDelay = "2h", initialDelay = "30s")
    public void validatePendingSentiments() {
        LOG.info("==> Price validation scheduled task FIRED (processing news 2+ hours old)");

        try {
            List<SentimentRecord> pending = fetchPendingSentiments();
            LOG.info("Fetched {} pending sentiments for validation (2+ hours old)", pending.size());
            if (pending.isEmpty()) {
                LOG.info("No pending sentiments older than 2h to validate");
                return;
            }

            LOG.info("Validating {} sentiments using intraday price data", pending.size());
            List<SentimentRecord> sorted = pending.stream()
                .sorted(Comparator.comparingDouble((SentimentRecord s) -> Math.abs(s.sentimentScore())).reversed())
                .toList();

            // Cache for SPY prices to avoid redundant API calls
            java.util.Map<Instant, Optional<PricePoint>> spyCache = new java.util.HashMap<>();

            int validated = 0;
            int apiCallCount = 0;
            for (SentimentRecord record : sorted) {
                // Rate limiting: 5 API calls/min for Alpha Vantage
                // Each validation = 2 stock calls (before + after)
                // Process 2 stocks/min = 4 calls/min (within 5 calls/min limit)
                if (apiCallCount > 0 && apiCallCount % 2 == 0) {
                    LOG.info("Rate limit: Processed {} stocks, sleeping 65s", apiCallCount);
                    Thread.sleep(65000); // 65s to stay under 5 calls/min
                }

                boolean success = validateSentiment(record, spyCache);
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
     * Fetches unvalidated sentiments from news older than 2 hours.
     */
    private List<SentimentRecord> fetchPendingSentiments() {
        String sql = """
            SELECT article_id, ticker, sentiment, news_timestamp
            FROM news_analyzed
            WHERE validated = false
              AND news_timestamp < now() - INTERVAL 2 HOUR
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
     * Validates a single sentiment using intraday prices:
     * - Before: 5 minutes before news published
     * - After: 30 minutes after news published
     * Includes volume analysis and S&P 500 benchmark comparison.
     */
    private boolean validateSentiment(SentimentRecord record, java.util.Map<Instant, Optional<PricePoint>> spyCache) {
        try {
            Instant newsTime = record.newsTimestamp().toInstant();
            ZonedDateTime newsET = newsTime.atZone(ET_ZONE);

            // Skip if outside market hours
            if (!isMarketHoursForNews(newsET)) {
                LOG.debug("Skipping {}: news published outside market hours", record.ticker());
                return false;
            }

            // Calculate time windows with market boundary checks
            Instant beforeTime = newsTime.minus(5, ChronoUnit.MINUTES);
            Instant afterTime = newsTime.plus(30, ChronoUnit.MINUTES);

            // Check if beforeTime is before market open (9:30 AM ET)
            ZonedDateTime marketOpen = newsET.toLocalDate()
                .atTime(9, 30)
                .atZone(ET_ZONE);

            if (beforeTime.isBefore(marketOpen.toInstant())) {
                LOG.debug("Skipping {}: before-time ({}) is before market open",
                    record.ticker(), beforeTime);
                return false;
            }

            // Check if afterTime is after market close (4:00 PM ET)
            ZonedDateTime marketClose = newsET.toLocalDate()
                .atTime(16, 0)
                .atZone(ET_ZONE);

            if (afterTime.isAfter(marketClose.toInstant())) {
                LOG.debug("Skipping {}: after-time ({}) is after market close",
                    record.ticker(), afterTime);
                return false;
            }

            Optional<PricePoint> priceBefore = getIntradayPrice(record.ticker(), beforeTime);
            Optional<PricePoint> priceAfter = getIntradayPrice(record.ticker(), afterTime);

            if (priceBefore.isEmpty() || priceAfter.isEmpty()) {
                LOG.warn("Could not fetch intraday prices for ticker: {} at {}", record.ticker(), newsTime);
                return false;
            }

            PricePoint pBefore = priceBefore.get();
            PricePoint pAfter = priceAfter.get();

            // Calculate price change
            double priceDiff = pAfter.close() - pBefore.close();
            double priceChangePct = (priceDiff / pBefore.close()) * 100.0;
            int actualDirection = priceDiff > 0 ? 1 : (priceDiff < 0 ? -1 : 0);
            int sentimentDirection = record.sentimentScore() > 0 ? 1 : (record.sentimentScore() < 0 ? -1 : 0);
            boolean predictionCorrect = (actualDirection == sentimentDirection) && (actualDirection != 0);

            // Calculate volume change
            long volumeBefore = pBefore.volume();
            long volumeAfter = pAfter.volume();
            double volumeChangePct = volumeBefore > 0 ?
                ((volumeAfter - volumeBefore) / (double) volumeBefore) * 100.0 : 0.0;

            // SPY benchmark commented out to reduce API calls (2 calls per stock instead of 4)
            // TODO: Re-enable later when needed for excess return analysis
            /*
            // Get S&P 500 (SPY) benchmark with caching
            Optional<PricePoint> spyBefore = spyCache.computeIfAbsent(beforeTime,
                t -> getIntradayPrice("SPY", t));
            Optional<PricePoint> spyAfter = spyCache.computeIfAbsent(afterTime,
                t -> getIntradayPrice("SPY", t));

            double spyChangePct = 0.0;
            double excessReturn = priceChangePct;

            if (spyBefore.isPresent() && spyAfter.isPresent()) {
                double spyDiff = spyAfter.get().close() - spyBefore.get().close();
                spyChangePct = (spyDiff / spyBefore.get().close()) * 100.0;
                excessReturn = priceChangePct - spyChangePct; // Alpha over market
            }
            */

            // Without SPY, excess return = raw stock return
            double spyChangePct = 0.0;
            double excessReturn = priceChangePct;

            saveValidationResult(
                record.articleId(),
                record.ticker(),
                pBefore.close(),
                pAfter.close(),
                priceDiff,
                priceChangePct,
                record.sentimentScore(),
                actualDirection,
                predictionCorrect,
                record.newsTimestamp(),
                "AlphaVantage-Intraday",
                volumeBefore,
                volumeAfter,
                volumeChangePct,
                spyChangePct,
                excessReturn
            );

            markAsValidated(record.articleId(), record.ticker());

            LOG.info("Validated {}: sentiment={:.2f}, price {:.2f}→{:.2f} ({:.2f}%), vol:{:.1f}%, excess:{:.2f}%, correct={}",
                record.ticker(), record.sentimentScore(), pBefore.close(), pAfter.close(),
                priceChangePct, volumeChangePct, excessReturn, predictionCorrect);

            return true;

        } catch (Exception e) {
            LOG.error("Failed to validate sentiment for ticker: {}", record.ticker(), e);
            return false;
        }
    }

    /**
     * Gets intraday price using Alpha Vantage.
     */
    private Optional<PricePoint> getIntradayPrice(String ticker, Instant timestamp) {
        return alphaVantageProvider.getIntradayPriceAt(ticker, timestamp);
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
            String provider,
            long volumeBefore,
            long volumeAfter,
            double volumeChangePct,
            double spyChangePct,
            double excessReturn) {

        String sql = """
            INSERT INTO signals_verified (
                news_sentiment_id, ticker, price_at_t, price_at_t_plus_1h,
                price_diff, price_change_pct, sentiment_score, actual_direction,
                prediction_correct, news_timestamp, validated_at, provider,
                volume_before, volume_after, volume_change_pct, spy_price_change_pct, excess_return
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
            ps.setLong(13, volumeBefore);
            ps.setLong(14, volumeAfter);
            ps.setDouble(15, volumeChangePct);
            ps.setDouble(16, spyChangePct);
            ps.setDouble(17, excessReturn);

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
