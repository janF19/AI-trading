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
import java.util.Iterator;
import java.util.Optional;

/**
 * Alpha Vantage API fallback for fetching daily stock prices.
 * Free tier: 25 requests/day
 */
@Singleton
public class AlphaVantagePriceProvider implements PriceDataProvider {
    private static final Logger LOG = LoggerFactory.getLogger(AlphaVantagePriceProvider.class);
    private static final String BASE_URL = "https://www.alphavantage.co/query";
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public AlphaVantagePriceProvider(
            @Value("${alphavantage.api.key}") String apiKey,
            HttpClient httpClient,
            ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        LOG.info("AlphaVantagePriceProvider initialized");
    }

    @Override
    public Optional<PricePoint> getPriceAt(String ticker, Instant timestamp) {
        try {
            LocalDate targetDate = timestamp.atZone(ZoneId.of("America/New_York")).toLocalDate();
            String dateStr = targetDate.format(DATE_FORMATTER);
            URI uri = UriBuilder.of(BASE_URL)
                    .queryParam("function", "TIME_SERIES_DAILY")
                    .queryParam("symbol", ticker)
                    .queryParam("outputsize", "compact")
                    .queryParam("apikey", apiKey)
                    .build();

            String response = httpClient.toBlocking().retrieve(uri.toString());
            JsonNode json = objectMapper.readTree(response);

            if (json.has("Error Message")) {
                LOG.warn("Alpha Vantage returned error: {} for ticker: {}", json.path("Error Message").asText(), ticker);
                return Optional.empty();
            }

            if (json.has("Note")) {
                LOG.warn("Alpha Vantage rate limit: {}", json.path("Note").asText());
                return Optional.empty();
            }

            if (json.has("Information")) {
                LOG.warn("Alpha Vantage info: {}", json.path("Information").asText());
                return Optional.empty();
            }

            JsonNode timeSeries = json.path("Time Series (Daily)");
            if (!timeSeries.isObject() || timeSeries.size() == 0) {
                LOG.warn("No daily data for ticker: {} (API response keys: {})", ticker, json.fieldNames().hasNext() ? json.fieldNames().next() : "empty");
                return Optional.empty();
            }

            JsonNode priceData = timeSeries.get(dateStr);
            if (priceData == null) {
                LocalDate closestDate = null;
                Iterator<String> dates = timeSeries.fieldNames();
                while (dates.hasNext()) {
                    String date = dates.next();
                    LocalDate currentDate = LocalDate.parse(date, DATE_FORMATTER);
                    if (currentDate.isBefore(targetDate) || currentDate.isEqual(targetDate)) {
                        if (closestDate == null || currentDate.isAfter(closestDate)) {
                            closestDate = currentDate;
                        }
                    }
                }

                if (closestDate == null) {
                    LOG.warn("No data found for ticker: {} before date: {}", ticker, dateStr);
                    return Optional.empty();
                }

                dateStr = closestDate.format(DATE_FORMATTER);
                priceData = timeSeries.get(dateStr);
            }

            PricePoint point = new PricePoint(
                ticker,
                LocalDate.parse(dateStr, DATE_FORMATTER).atStartOfDay(ZoneId.of("America/New_York")).toInstant(),
                priceData.path("1. open").asDouble(0.0),
                priceData.path("2. high").asDouble(0.0),
                priceData.path("3. low").asDouble(0.0),
                priceData.path("4. close").asDouble(0.0),
                priceData.path("5. volume").asLong(0L)
            );

            LOG.debug("Alpha Vantage fetched price for {}: close=${} at {}", ticker, point.close(), dateStr);
            return Optional.of(point);

        } catch (Exception e) {
            LOG.error("Failed to fetch price from Alpha Vantage for ticker: {} at {}", ticker, timestamp, e);
            return Optional.empty();
        }
    }

    @Override
    public boolean isValidTicker(String ticker) {
        try {
            URI uri = UriBuilder.of(BASE_URL)
                    .queryParam("function", "GLOBAL_QUOTE")
                    .queryParam("symbol", ticker)
                    .queryParam("apikey", apiKey)
                    .build();

            String response = httpClient.toBlocking().retrieve(uri.toString());
            JsonNode json = objectMapper.readTree(response);

            // Valid ticker will have a global quote
            JsonNode quote = json.path("Global Quote");
            boolean valid = quote.isObject() && quote.size() > 0;

            LOG.debug("Alpha Vantage ticker {} validation: {}", ticker, valid);
            return valid;

        } catch (Exception e) {
            LOG.error("Failed to validate ticker with Alpha Vantage: {}", ticker, e);
            return false;
        }
    }

    @Override
    public String getProviderName() {
        return "AlphaVantage";
    }
}
