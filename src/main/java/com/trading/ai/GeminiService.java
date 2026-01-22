package com.trading.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Singleton
public class GeminiService {
    private static final Logger LOG = LoggerFactory.getLogger(GeminiService.class);
    private final ChatLanguageModel model;
    private final ObjectMapper objectMapper;

    public GeminiService(@Value("${gemini.api.key}") String apiKey, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.model = GoogleAiGeminiChatModel.builder()
                .apiKey(apiKey)
                .modelName("gemini-2.5-flash")
                .temperature(0.1)
                .build();
        LOG.info("GeminiService initialized with gemini-2.5-flash");
    }

    public record NewsArticle(String id, String headline, String summary) {}

    public Map<String, SentimentResult> analyzeBatch(List<NewsArticle> articles) {
        String prompt = buildBatchPrompt(articles);

        try {
            String response = model.generate(prompt);
            LOG.debug("Gemini batch response: {}", response);
            return parseBatchResponse(response, articles);
        } catch (Exception e) {
            LOG.error("Error calling Gemini API for batch", e);
            return new HashMap<>();
        }
    }

    private String buildBatchPrompt(List<NewsArticle> articles) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
            Analyze these news articles for stock market relevance and sentiment.

            Rules:
            1. Market Relevant = mentions specific companies, stocks, sectors, markets, economic policy, trade, tariffs, or major macro events
            2. NOT Relevant = consumer advice (buying TVs, personal finance tips), general lifestyle news
            3. Extract ONLY ONE primary ticker symbol - the stock most impacted by this news (e.g., AAPL, TSLA, NFLX)
               - If multiple companies mentioned, pick the one MOST directly affected
               - If no clear single ticker, set marketRelevant=false and tickers=[]
            4. Sentiment score: -1.0 (very bearish) to +1.0 (very bullish), 0.0 if not market relevant
            5. Consider: company performance, market conditions, policy changes, trade tensions

            Articles:
            """);

        for (int i = 0; i < articles.size(); i++) {
            NewsArticle article = articles.get(i);
            sb.append(String.format("\n[%d] ID: %s\nHeadline: %s\nSummary: %s\n",
                i, article.id(), article.headline(), article.summary() != null ? article.summary() : ""));
        }

        sb.append("""

            Respond ONLY in this JSON array format:
            [
              {
                "id": "article_id",
                "marketRelevant": true,
                "tickers": ["NFLX"],
                "sentiment": 0.8,
                "reasoning": "brief explanation"
              }
            ]
            Include ALL articles, even if not market relevant (set marketRelevant=false, tickers=[], sentiment=0.0).
            """);

        return sb.toString();
    }

    private Map<String, SentimentResult> parseBatchResponse(String response, List<NewsArticle> articles) {
        Map<String, SentimentResult> results = new HashMap<>();
        try {
            String json = response.trim();
            if (json.startsWith("```")) {
                json = json.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
            }

            JsonNode array = objectMapper.readTree(json);
            if (!array.isArray()) {
                LOG.error("Expected JSON array, got: {}", response);
                return results;
            }

            for (JsonNode node : array) {
                String id = node.path("id").asText("");
                boolean marketRelevant = node.path("marketRelevant").asBoolean(false);
                double sentiment = node.path("sentiment").asDouble(0.0);
                String reasoning = node.path("reasoning").asText("");

                List<String> tickers = new ArrayList<>();
                if (node.has("tickers") && node.get("tickers").isArray()) {
                    node.get("tickers").forEach(t -> tickers.add(t.asText().trim()));
                }

                results.put(id, new SentimentResult(marketRelevant, tickers, sentiment, reasoning));
            }

            LOG.info("Parsed {} results from batch", results.size());
        } catch (Exception e) {
            LOG.error("Failed to parse batch response: {}", response, e);
        }
        return results;
    }
}
