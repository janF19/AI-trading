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
 * TwelveData API implementation for fetching daily stock prices.
 * Free tier: 800 requests/day
 */
@Singleton
public class TwelveDataPriceProvider implements PriceDataProvider {
    private static final Logger LOG = LoggerFactory.getLogger(TwelveDataPriceProvider.class);
    private static final String BASE_URL = "https://api.twelvedata.com";
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public TwelveDataPriceProvider(
            @Value("${twelvedata.api.key}") String apiKey,
            HttpClient httpClient,
            ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        LOG.info("TwelveDataPriceProvider initialized");
    }

    @Override
    public Optional<PricePoint> getPriceAt(String ticker, Instant timestamp) {
        try {
            ZoneId etZone = ZoneId.of("America/New_York");
            LocalDate targetDate = timestamp.atZone(etZone).toLocalDate();
            String dateStr = targetDate.format(DATE_FORMATTER);
            URI uri = UriBuilder.of(BASE_URL)
                    .path("/time_series")
                    .queryParam("symbol", ticker)
                    .queryParam("interval", "1day")
                    .queryParam("outputsize", "5")
                    .queryParam("end_date", dateStr)
                    .queryParam("apikey", apiKey)
                    .build();

            String response = httpClient.toBlocking().retrieve(uri.toString());
            JsonNode json = objectMapper.readTree(response);

            if (json.has("code") && json.get("code").asInt() != 200) {
                LOG.warn("TwelveData returned error: {} for ticker: {}", json.path("message").asText(), ticker);
                return Optional.empty();
            }

            JsonNode values = json.path("values");
            if (!values.isArray() || values.size() == 0) {
                LOG.warn("No daily data for ticker: {} at date: {}", ticker, dateStr);
                return Optional.empty();
            }

            JsonNode priceData = findClosestDay(values, targetDate);
            if (priceData == null) {
                LOG.warn("Could not find trading day for ticker: {} near {}", ticker, dateStr);
                return Optional.empty();
            }

            String actualDate = priceData.path("datetime").asText();

            PricePoint point = new PricePoint(
                ticker,
                LocalDate.parse(actualDate, DATE_FORMATTER).atStartOfDay(etZone).toInstant(),
                parseDouble(priceData, "open"),
                parseDouble(priceData, "high"),
                parseDouble(priceData, "low"),
                parseDouble(priceData, "close"),
                parseLong(priceData, "volume")
            );

            LOG.debug("TwelveData fetched DAILY price for {}: close=${} at {}", ticker, point.close(), actualDate);
            return Optional.of(point);

        } catch (Exception e) {
            LOG.error("Failed to fetch price from TwelveData for ticker: {} at {}", ticker, timestamp, e);
            return Optional.empty();
        }
    }

    @Override
    public boolean isValidTicker(String ticker) {
        try {
            URI uri = UriBuilder.of(BASE_URL)
                    .path("/quote")
                    .queryParam("symbol", ticker)
                    .queryParam("apikey", apiKey)
                    .build();

            String response = httpClient.toBlocking().retrieve(uri.toString());
            JsonNode json = objectMapper.readTree(response);

            // Valid ticker will have a close price
            boolean valid = json.has("close") && !json.path("close").isNull();
            LOG.debug("TwelveData ticker {} validation: {}", ticker, valid);
            return valid;

        } catch (Exception e) {
            LOG.error("Failed to validate ticker with TwelveData: {}", ticker, e);
            return false;
        }
    }

    @Override
    public String getProviderName() {
        return "TwelveData";
    }

    private JsonNode findClosestDay(JsonNode values, LocalDate targetDate) {
        JsonNode closest = null;
        long minDiff = Long.MAX_VALUE;

        for (JsonNode value : values) {
            String dateStr = value.path("datetime").asText();
            try {
                LocalDate candleDate = LocalDate.parse(dateStr, DATE_FORMATTER);
                long diff = Math.abs(java.time.temporal.ChronoUnit.DAYS.between(candleDate, targetDate));
                if (diff < minDiff) {
                    minDiff = diff;
                    closest = value;
                }
            } catch (Exception e) {
                LOG.debug("Could not parse date: {}", dateStr);
            }
        }

        return closest;
    }

    private double parseDouble(JsonNode node, String field) {
        String value = node.path(field).asText();
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private long parseLong(JsonNode node, String field) {
        String value = node.path(field).asText();
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
