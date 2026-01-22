package com.trading.analytics;

import io.micronaut.serde.annotation.Serdeable;
import java.util.ArrayList;
import java.util.List;

@Serdeable
public class PriceMovementMetrics {
    private double averagePriceChange;
    private double maxPriceChange;
    private double minPriceChange;
    private List<PriceDistribution> distribution = new ArrayList<>();
    private List<TickerPerformance> topPerformers = new ArrayList<>();
    private List<TickerPerformance> worstPerformers = new ArrayList<>();

    public double getAveragePriceChange() {
        return averagePriceChange;
    }

    public void setAveragePriceChange(double averagePriceChange) {
        this.averagePriceChange = averagePriceChange;
    }

    public double getMaxPriceChange() {
        return maxPriceChange;
    }

    public void setMaxPriceChange(double maxPriceChange) {
        this.maxPriceChange = maxPriceChange;
    }

    public double getMinPriceChange() {
        return minPriceChange;
    }

    public void setMinPriceChange(double minPriceChange) {
        this.minPriceChange = minPriceChange;
    }

    public List<PriceDistribution> getDistribution() {
        return distribution;
    }

    public void setDistribution(List<PriceDistribution> distribution) {
        this.distribution = distribution;
    }

    public List<TickerPerformance> getTopPerformers() {
        return topPerformers;
    }

    public void setTopPerformers(List<TickerPerformance> topPerformers) {
        this.topPerformers = topPerformers;
    }

    public List<TickerPerformance> getWorstPerformers() {
        return worstPerformers;
    }

    public void setWorstPerformers(List<TickerPerformance> worstPerformers) {
        this.worstPerformers = worstPerformers;
    }

    @Serdeable
    public static class PriceDistribution {
        private String range;
        private int count;

        public PriceDistribution() {}

        public PriceDistribution(String range, int count) {
            this.range = range;
            this.count = count;
        }

        public String getRange() {
            return range;
        }

        public void setRange(String range) {
            this.range = range;
        }

        public int getCount() {
            return count;
        }

        public void setCount(int count) {
            this.count = count;
        }
    }

    @Serdeable
    public static class TickerPerformance {
        private String ticker;
        private double avgPriceChange;
        private int predictions;
        private double accuracy;

        public String getTicker() {
            return ticker;
        }

        public void setTicker(String ticker) {
            this.ticker = ticker;
        }

        public double getAvgPriceChange() {
            return avgPriceChange;
        }

        public void setAvgPriceChange(double avgPriceChange) {
            this.avgPriceChange = avgPriceChange;
        }

        public int getPredictions() {
            return predictions;
        }

        public void setPredictions(int predictions) {
            this.predictions = predictions;
        }

        public double getAccuracy() {
            return accuracy;
        }

        public void setAccuracy(double accuracy) {
            this.accuracy = accuracy;
        }
    }
}

