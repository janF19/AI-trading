package com.trading.pricing;

import io.micronaut.context.annotation.Value;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.uri.UriBuilder;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * Polygon.io API for fetching daily and intraday stock prices.
 * Provides real-time and historical market data.
 * Supports dual API key fallback to handle rate limits.
 */
@Singleton
public class AlphaVantagePriceProvider implements PriceDataProvider {
    private static final Logger LOG = LoggerFactory.getLogger(AlphaVantagePriceProvider.class);
    private static final String BASE_URL = "https://api.polygon.io";
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final ZoneId NY_ZONE = ZoneId.of("America/New_York");

    private final String apiKey;
    private final String apiKey2;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private volatile boolean useSecondKey = false;

    public AlphaVantagePriceProvider(
            @Value("${polygon.api.key}") String apiKey,
            @Value("${polygon.api.key2:}") String apiKey2,
            HttpClient httpClient,
            ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.apiKey2 = apiKey2;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        LOG.info("Polygon.io PriceProvider initialized with {} API key(s)",
            apiKey2.isEmpty() ? "1" : "2");
    }

    private String getCurrentApiKey() {
        return (useSecondKey && !apiKey2.isEmpty()) ? apiKey2 : apiKey;
    }

    private void switchToSecondKey() {
        if (!apiKey2.isEmpty() && !useSecondKey) {
            LOG.warn("Switching to second Polygon.io API key due to rate limit");
            useSecondKey = true;
        }
    }

    private boolean isRateLimitError(JsonNode json) {
        if (json.has("status") && json.path("status").asText().equals("ERROR")) {
            String error = json.path("error").asText().toLowerCase();
            return error.contains("limit") || error.contains("exceeded");
        }
        return false;
    }

    @Override
    public Optional<PricePoint> getPriceAt(String ticker, Instant timestamp) {
        try {
            LocalDate targetDate = timestamp.atZone(NY_ZONE).toLocalDate();
            String dateStr = targetDate.format(DATE_FORMATTER);

            // Polygon.io daily aggregates endpoint: /v2/aggs/ticker/{ticker}/range/1/day/{from}/{to}
            URI uri = UriBuilder.of(BASE_URL)
                    .path("/v2/aggs/ticker/" + ticker + "/range/1/day/" + dateStr + "/" + dateStr)
                    .queryParam("adjusted", "true")
                    .queryParam("sort", "asc")
                    .queryParam("apiKey", getCurrentApiKey())
                    .build();

            String response = httpClient.toBlocking().retrieve(uri.toString());
            JsonNode json = objectMapper.readTree(response);

            if (isRateLimitError(json)) {
                LOG.warn("Polygon.io rate limit hit: {}", json.path("error").asText());
                switchToSecondKey();
                return Optional.empty();
            }

            if (json.has("error")) {
                LOG.warn("Polygon.io returned error: {} for ticker: {}", json.path("error").asText(), ticker);
                return Optional.empty();
            }

            if (!json.has("results") || json.path("resultsCount").asInt(0) == 0) {
                LOG.warn("No daily data for ticker: {} on {}", ticker, dateStr);
                return Optional.empty();
            }

            JsonNode results = json.path("results");
            if (!results.isArray() || results.size() == 0) {
                LOG.warn("Empty results array for ticker: {}", ticker);
                return Optional.empty();
            }

            JsonNode priceData = results.get(0);
            long timestampMs = priceData.path("t").asLong();
            Instant dataInstant = Instant.ofEpochMilli(timestampMs);

            PricePoint point = new PricePoint(
                ticker,
                dataInstant,
                priceData.path("o").asDouble(0.0),  // open
                priceData.path("h").asDouble(0.0),  // high
                priceData.path("l").asDouble(0.0),  // low
                priceData.path("c").asDouble(0.0),  // close
                priceData.path("v").asLong(0L)      // volume
            );

            LOG.debug("Polygon.io fetched price for {}: close=${} at {}", ticker, point.close(), dateStr);
            return Optional.of(point);

        } catch (Exception e) {
            LOG.error("Failed to fetch price from Polygon.io for ticker: {} at {}", ticker, timestamp, e);
            return Optional.empty();
        }
    }

    /**
     * Fetches intraday price data (5-min intervals) around the given timestamp.
     * Returns price point closest to target time within +/- 30 minutes.
     */
    public Optional<PricePoint> getIntradayPriceAt(String ticker, Instant timestamp) {
        try {
            LocalDate targetDate = timestamp.atZone(NY_ZONE).toLocalDate();
            String dateStr = targetDate.format(DATE_FORMATTER);

            // Polygon.io intraday aggregates: /v2/aggs/ticker/{ticker}/range/{multiplier}/{timespan}/{from}/{to}
            URI uri = UriBuilder.of(BASE_URL)
                    .path("/v2/aggs/ticker/" + ticker + "/range/5/minute/" + dateStr + "/" + dateStr)
                    .queryParam("adjusted", "true")
                    .queryParam("sort", "asc")
                    .queryParam("apiKey", getCurrentApiKey())
                    .build();

            String response = httpClient.toBlocking().retrieve(uri.toString());
            JsonNode json = objectMapper.readTree(response);

            if (isRateLimitError(json)) {
                LOG.warn("Polygon.io intraday rate limit hit: {}", json.path("error").asText());
                switchToSecondKey();
                return Optional.empty();
            }

            if (json.has("error")) {
                LOG.warn("Polygon.io intraday error: {} for ticker: {}", json.path("error").asText(), ticker);
                return Optional.empty();
            }

            if (!json.has("results") || json.path("resultsCount").asInt(0) == 0) {
                LOG.warn("No intraday data for ticker: {} on {}", ticker, dateStr);
                return Optional.empty();
            }

            JsonNode results = json.path("results");
            if (!results.isArray() || results.size() == 0) {
                return Optional.empty();
            }

            // Find closest timestamp within +/- 30 minutes (1800 seconds)
            PricePoint closest = null;
            long minDiff = Long.MAX_VALUE;

            for (JsonNode bar : results) {
                long barTimestampMs = bar.path("t").asLong();
                Instant barInstant = Instant.ofEpochMilli(barTimestampMs);
                long diff = Math.abs(barInstant.getEpochSecond() - timestamp.getEpochSecond());

                if (diff <= 1800 && diff < minDiff) {
                    minDiff = diff;
                    closest = new PricePoint(
                        ticker,
                        barInstant,
                        bar.path("o").asDouble(0.0),  // open
                        bar.path("h").asDouble(0.0),  // high
                        bar.path("l").asDouble(0.0),  // low
                        bar.path("c").asDouble(0.0),  // close
                        bar.path("v").asLong(0L)      // volume
                    );
                }
            }

            if (closest != null) {
                LOG.debug("Polygon.io intraday: {} at {} ({}min from target)",
                    ticker, closest.timestamp(), minDiff / 60);
                return Optional.of(closest);
            }

            LOG.warn("No intraday data within 30min of target for {} at {}", ticker, timestamp);
            return Optional.empty();

        } catch (Exception e) {
            LOG.error("Failed to fetch intraday price from Polygon.io for ticker: {}", ticker, e);
            return Optional.empty();
        }
    }

    @Override
    public boolean isValidTicker(String ticker) {
        try {
            // Use Polygon.io ticker details endpoint: /v3/reference/tickers/{ticker}
            URI uri = UriBuilder.of(BASE_URL)
                    .path("/v3/reference/tickers/" + ticker)
                    .queryParam("apiKey", getCurrentApiKey())
                    .build();

            String response = httpClient.toBlocking().retrieve(uri.toString());
            JsonNode json = objectMapper.readTree(response);

            if (isRateLimitError(json)) {
                LOG.warn("Polygon.io ticker validation rate limit hit");
                switchToSecondKey();
                return false;
            }

            // Valid ticker will have results
            boolean valid = json.has("results") && json.path("results").has("ticker");

            LOG.debug("Polygon.io ticker {} validation: {}", ticker, valid);
            return valid;

        } catch (Exception e) {
            LOG.error("Failed to validate ticker with Polygon.io: {}", ticker, e);
            return false;
        }
    }

    @Override
    public String getProviderName() {
        return "Polygon.io";
    }
}
