package com.trading.analytics;

import io.micronaut.serde.annotation.Serdeable;
import java.util.ArrayList;
import java.util.List;

@Serdeable
public class NewsSentimentMetrics {
    private long totalRawNews;
    private long totalAnalyzed;
    private double analysisCompletionRate;
    private SentimentDistribution sentimentDistribution = new SentimentDistribution();
    private List<TickerNewsVolume> topTickersByVolume = new ArrayList<>();

    public long getTotalRawNews() {
        return totalRawNews;
    }

    public void setTotalRawNews(long totalRawNews) {
        this.totalRawNews = totalRawNews;
    }

    public long getTotalAnalyzed() {
        return totalAnalyzed;
    }

    public void setTotalAnalyzed(long totalAnalyzed) {
        this.totalAnalyzed = totalAnalyzed;
    }

    public double getAnalysisCompletionRate() {
        return analysisCompletionRate;
    }

    public void setAnalysisCompletionRate(double analysisCompletionRate) {
        this.analysisCompletionRate = analysisCompletionRate;
    }

    public SentimentDistribution getSentimentDistribution() {
        return sentimentDistribution;
    }

    public void setSentimentDistribution(SentimentDistribution sentimentDistribution) {
        this.sentimentDistribution = sentimentDistribution;
    }

    public List<TickerNewsVolume> getTopTickersByVolume() {
        return topTickersByVolume;
    }

    public void setTopTickersByVolume(List<TickerNewsVolume> topTickersByVolume) {
        this.topTickersByVolume = topTickersByVolume;
    }

    @Serdeable
    public static class SentimentDistribution {
        private int positive;
        private int negative;
        private int neutral;

        public int getPositive() {
            return positive;
        }

        public void setPositive(int positive) {
            this.positive = positive;
        }

        public int getNegative() {
            return negative;
        }

        public void setNegative(int negative) {
            this.negative = negative;
        }

        public int getNeutral() {
            return neutral;
        }

        public void setNeutral(int neutral) {
            this.neutral = neutral;
        }
    }

    @Serdeable
    public static class TickerNewsVolume {
        private String ticker;
        private long newsCount;

        public String getTicker() {
            return ticker;
        }

        public void setTicker(String ticker) {
            this.ticker = ticker;
        }

        public long getNewsCount() {
            return newsCount;
        }

        public void setNewsCount(long newsCount) {
            this.newsCount = newsCount;
        }
    }
}

