package com.trading.analytics;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public class SignalAccuracyMetrics {
    private double overallAccuracyRate;
    private int totalSignals;
    private int correctPredictions;
    private int incorrectPredictions;
    private AccuracyBySentiment strongPositive;
    private AccuracyBySentiment strongNegative;
    private AccuracyBySentiment weakSignals;

    // Getters and setters
    public double getOverallAccuracyRate() {
        return overallAccuracyRate;
    }

    public void setOverallAccuracyRate(double overallAccuracyRate) {
        this.overallAccuracyRate = overallAccuracyRate;
    }

    public int getTotalSignals() {
        return totalSignals;
    }

    public void setTotalSignals(int totalSignals) {
        this.totalSignals = totalSignals;
    }

    public int getCorrectPredictions() {
        return correctPredictions;
    }

    public void setCorrectPredictions(int correctPredictions) {
        this.correctPredictions = correctPredictions;
    }

    public int getIncorrectPredictions() {
        return incorrectPredictions;
    }

    public void setIncorrectPredictions(int incorrectPredictions) {
        this.incorrectPredictions = incorrectPredictions;
    }

    public AccuracyBySentiment getStrongPositive() {
        return strongPositive;
    }

    public void setStrongPositive(AccuracyBySentiment strongPositive) {
        this.strongPositive = strongPositive;
    }

    public AccuracyBySentiment getStrongNegative() {
        return strongNegative;
    }

    public void setStrongNegative(AccuracyBySentiment strongNegative) {
        this.strongNegative = strongNegative;
    }

    public AccuracyBySentiment getWeakSignals() {
        return weakSignals;
    }

    public void setWeakSignals(AccuracyBySentiment weakSignals) {
        this.weakSignals = weakSignals;
    }

    @Serdeable
    public static class AccuracyBySentiment {
        private String category;
        private double accuracyRate;
        private int total;
        private int correct;

        public String getCategory() {
            return category;
        }

        public void setCategory(String category) {
            this.category = category;
        }

        public double getAccuracyRate() {
            return accuracyRate;
        }

        public void setAccuracyRate(double accuracyRate) {
            this.accuracyRate = accuracyRate;
        }

        public int getTotal() {
            return total;
        }

        public void setTotal(int total) {
            this.total = total;
        }

        public int getCorrect() {
            return correct;
        }

        public void setCorrect(int correct) {
            this.correct = correct;
        }
    }
}

