package com.trading.analytics;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@Controller("/analytics")
public class AnalyticsController {
    private static final Logger LOG = LoggerFactory.getLogger(AnalyticsController.class);
    private final AnalyticsService analyticsService;

    public AnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @Get(produces = MediaType.TEXT_HTML)
    public HttpResponse<String> dashboard() {
        try {
            InputStream inputStream = getClass().getClassLoader()
                .getResourceAsStream("views/dashboard.html");
            if (inputStream == null) {
                return HttpResponse.notFound("Dashboard not found");
            }
            String html = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            return HttpResponse.ok(html).contentType(MediaType.TEXT_HTML);
        } catch (Exception e) {
            LOG.error("Error loading dashboard", e);
            return HttpResponse.serverError("Error loading dashboard");
        }
    }

    @Get("/api/signal-accuracy")
    public SignalAccuracyMetrics getSignalAccuracy() {
        LOG.info("Fetching signal accuracy metrics");
        return analyticsService.getSignalAccuracyMetrics();
    }

    @Get("/api/price-movements")
    public PriceMovementMetrics getPriceMovements() {
        LOG.info("Fetching price movement metrics");
        return analyticsService.getPriceMovementMetrics();
    }

    @Get("/api/news-sentiment")
    public NewsSentimentMetrics getNewsSentiment() {
        LOG.info("Fetching news sentiment metrics");
        return analyticsService.getNewsSentimentMetrics();
    }
}

