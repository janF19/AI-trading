package com.trading.ingestion;

import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import io.micronaut.configuration.kafka.annotation.KafkaClient;
import io.micronaut.configuration.kafka.annotation.Topic;
import io.micronaut.context.annotation.Value;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.uri.UriBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.UUID;

@Singleton
public class NewsProducer {
    private static final Logger LOG = LoggerFactory.getLogger(NewsProducer.class);
    private final NewsClient newsClient;
    private final HttpClient httpClient;

    @Value("${finnhub.api.key:demo}")
    private String apiKey;

    public NewsProducer(NewsClient newsClient, HttpClient httpClient) {
        this.newsClient = newsClient;
        this.httpClient = httpClient;
    }

    @Scheduled(fixedDelay = "15m")  // Poll every 15 minutes to avoid duplicates
    public void pollNews() {
        LOG.info("Polling Finnhub for market news...");
        try {
            String url = UriBuilder.of("https://finnhub.io/api/v1/news")
                    .queryParam("category", "general")
                    .queryParam("token", apiKey)
                    .build()
                    .toString();

            String response = httpClient.toBlocking().retrieve(url);

            String messageId = UUID.randomUUID().toString();
            newsClient.sendNews(messageId, response);

            LOG.info("Successfully fetched and sent news to Kafka");
        } catch (Exception e) {
            LOG.error("Failed to fetch news from Finnhub", e);
        }
    }
}

@KafkaClient
interface NewsClient {
    @Topic("news.live")
    void sendNews(@io.micronaut.configuration.kafka.annotation.KafkaKey String id, String newsJson);
}
