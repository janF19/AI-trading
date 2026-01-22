package com.trading.pricing;

import java.time.Instant;
import java.util.Optional;

/**
 * Provider interface for fetching stock price data from various APIs.
 * Implementations should handle rate limiting and fallback strategies.
 */
public interface PriceDataProvider {

    /**
     * Fetches the stock price at a specific timestamp using hourly candles.
     *
     * @param ticker Stock ticker symbol (e.g., "AAPL", "TSLA")
     * @param timestamp The exact time to fetch the price for
     * @return PricePoint containing the price data, or empty if not available
     */
    Optional<PricePoint> getPriceAt(String ticker, Instant timestamp);

    /**
     * Validates if a ticker symbol exists and is tradable.
     *
     * @param ticker Stock ticker symbol to validate
     * @return true if ticker is valid and tradable
     */
    boolean isValidTicker(String ticker);

    /**
     * Returns the provider name for logging and monitoring.
     */
    String getProviderName();

    /**
     * Represents a price point at a specific time.
     */
    record PricePoint(
        String ticker,
        Instant timestamp,
        double open,
        double high,
        double low,
        double close,
        long volume
    ) {}
}
