package com.trading.ai;

import io.micronaut.serde.annotation.Serdeable;
import java.util.List;

@Serdeable
public record SentimentResult(
    boolean marketRelevant,
    List<String> tickers,
    double sentiment,
    String reasoning
) {}
