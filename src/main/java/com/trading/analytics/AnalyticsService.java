package com.trading.analytics;

import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

@Singleton
public class AnalyticsService {
    private static final Logger LOG = LoggerFactory.getLogger(AnalyticsService.class);
    private final DataSource dataSource;

    public AnalyticsService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public SignalAccuracyMetrics getSignalAccuracyMetrics() {
        SignalAccuracyMetrics metrics = new SignalAccuracyMetrics();
        
        try (Connection conn = dataSource.getConnection()) {
            // Overall accuracy
            String overallSql = """
                SELECT 
                    COUNT(*) as total,
                    SUM(CASE WHEN prediction_correct = 1 THEN 1 ELSE 0 END) as correct,
                    SUM(CASE WHEN prediction_correct = 0 THEN 1 ELSE 0 END) as incorrect,
                    AVG(CASE WHEN prediction_correct = 1 THEN 100.0 ELSE 0.0 END) as accuracy_rate
                FROM signals_verified
            """;
            
            try (PreparedStatement stmt = conn.prepareStatement(overallSql);
                 ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    metrics.setTotalSignals(rs.getInt("total"));
                    metrics.setCorrectPredictions(rs.getInt("correct"));
                    metrics.setIncorrectPredictions(rs.getInt("incorrect"));
                    metrics.setOverallAccuracyRate(rs.getDouble("accuracy_rate"));
                }
            }

            // Accuracy by sentiment strength
            // Strong positive: sentiment > 0.6
            metrics.setStrongPositive(getAccuracyBySentimentRange(conn, "Strong Positive", 0.6, 1.0));
            
            // Strong negative: sentiment < -0.6
            metrics.setStrongNegative(getAccuracyBySentimentRange(conn, "Strong Negative", -1.0, -0.6));
            
            // Weak signals: -0.6 <= sentiment <= 0.6
            metrics.setWeakSignals(getAccuracyBySentimentRange(conn, "Weak Signals", -0.6, 0.6));
            
        } catch (Exception e) {
            LOG.error("Error calculating signal accuracy metrics", e);
        }
        
        return metrics;
    }

    private SignalAccuracyMetrics.AccuracyBySentiment getAccuracyBySentimentRange(
            Connection conn, String category, double minSentiment, double maxSentiment) throws Exception {
        
        String sql = """
            SELECT 
                COUNT(*) as total,
                SUM(CASE WHEN prediction_correct = 1 THEN 1 ELSE 0 END) as correct,
                AVG(CASE WHEN prediction_correct = 1 THEN 100.0 ELSE 0.0 END) as accuracy_rate
            FROM signals_verified
            WHERE sentiment_score >= ? AND sentiment_score < ?
        """;
        
        SignalAccuracyMetrics.AccuracyBySentiment result = new SignalAccuracyMetrics.AccuracyBySentiment();
        result.setCategory(category);
        
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setDouble(1, minSentiment);
            stmt.setDouble(2, maxSentiment);
            
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    result.setTotal(rs.getInt("total"));
                    result.setCorrect(rs.getInt("correct"));
                    result.setAccuracyRate(rs.getDouble("accuracy_rate"));
                }
            }
        }
        
        return result;
    }

    public PriceMovementMetrics getPriceMovementMetrics() {
        PriceMovementMetrics metrics = new PriceMovementMetrics();
        
        try (Connection conn = dataSource.getConnection()) {
            // Average, max, min price changes
            String statsSql = """
                SELECT 
                    AVG(price_change_pct) as avg_change,
                    MAX(price_change_pct) as max_change,
                    MIN(price_change_pct) as min_change
                FROM signals_verified
            """;
            
            try (PreparedStatement stmt = conn.prepareStatement(statsSql);
                 ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    metrics.setAveragePriceChange(rs.getDouble("avg_change"));
                    metrics.setMaxPriceChange(rs.getDouble("max_change"));
                    metrics.setMinPriceChange(rs.getDouble("min_change"));
                }
            }

            // Price change distribution (histogram buckets)
            List<PriceMovementMetrics.PriceDistribution> distribution = new ArrayList<>();
            String distSql = """
                SELECT 
                    CASE 
                        WHEN price_change_pct < -5 THEN '< -5%'
                        WHEN price_change_pct < -2 THEN '-5% to -2%'
                        WHEN price_change_pct < -1 THEN '-2% to -1%'
                        WHEN price_change_pct < 0 THEN '-1% to 0%'
                        WHEN price_change_pct = 0 THEN '0%'
                        WHEN price_change_pct <= 1 THEN '0% to 1%'
                        WHEN price_change_pct <= 2 THEN '1% to 2%'
                        WHEN price_change_pct <= 5 THEN '2% to 5%'
                        ELSE '> 5%'
                    END as range,
                    COUNT(*) as count
                FROM signals_verified
                GROUP BY range
                ORDER BY 
                    CASE 
                        WHEN range = '< -5%' THEN 1
                        WHEN range = '-5% to -2%' THEN 2
                        WHEN range = '-2% to -1%' THEN 3
                        WHEN range = '-1% to 0%' THEN 4
                        WHEN range = '0%' THEN 5
                        WHEN range = '0% to 1%' THEN 6
                        WHEN range = '1% to 2%' THEN 7
                        WHEN range = '2% to 5%' THEN 8
                        WHEN range = '> 5%' THEN 9
                    END
            """;
            
            try (PreparedStatement stmt = conn.prepareStatement(distSql);
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    distribution.add(new PriceMovementMetrics.PriceDistribution(
                        rs.getString("range"),
                        rs.getInt("count")
                    ));
                }
            }
            metrics.setDistribution(distribution);

            // Top performers (best avg price changes with accuracy)
            List<PriceMovementMetrics.TickerPerformance> topPerformers = new ArrayList<>();
            String topSql = """
                SELECT 
                    ticker,
                    AVG(price_change_pct) as avg_price_change,
                    COUNT(*) as predictions,
                    AVG(CASE WHEN prediction_correct = 1 THEN 100.0 ELSE 0.0 END) as accuracy
                FROM signals_verified
                GROUP BY ticker
                HAVING COUNT(*) >= 3
                ORDER BY avg_price_change DESC
                LIMIT 10
            """;
            
            try (PreparedStatement stmt = conn.prepareStatement(topSql);
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    PriceMovementMetrics.TickerPerformance perf = new PriceMovementMetrics.TickerPerformance();
                    perf.setTicker(rs.getString("ticker"));
                    perf.setAvgPriceChange(rs.getDouble("avg_price_change"));
                    perf.setPredictions(rs.getInt("predictions"));
                    perf.setAccuracy(rs.getDouble("accuracy"));
                    topPerformers.add(perf);
                }
            }
            metrics.setTopPerformers(topPerformers);

            // Worst performers
            List<PriceMovementMetrics.TickerPerformance> worstPerformers = new ArrayList<>();
            String worstSql = """
                SELECT 
                    ticker,
                    AVG(price_change_pct) as avg_price_change,
                    COUNT(*) as predictions,
                    AVG(CASE WHEN prediction_correct = 1 THEN 100.0 ELSE 0.0 END) as accuracy
                FROM signals_verified
                GROUP BY ticker
                HAVING COUNT(*) >= 3
                ORDER BY avg_price_change ASC
                LIMIT 10
            """;
            
            try (PreparedStatement stmt = conn.prepareStatement(worstSql);
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    PriceMovementMetrics.TickerPerformance perf = new PriceMovementMetrics.TickerPerformance();
                    perf.setTicker(rs.getString("ticker"));
                    perf.setAvgPriceChange(rs.getDouble("avg_price_change"));
                    perf.setPredictions(rs.getInt("predictions"));
                    perf.setAccuracy(rs.getDouble("accuracy"));
                    worstPerformers.add(perf);
                }
            }
            metrics.setWorstPerformers(worstPerformers);
            
        } catch (Exception e) {
            LOG.error("Error calculating price movement metrics", e);
        }
        
        return metrics;
    }

    public NewsSentimentMetrics getNewsSentimentMetrics() {
        NewsSentimentMetrics metrics = new NewsSentimentMetrics();
        
        try (Connection conn = dataSource.getConnection()) {
            // Total news counts
            String countSql = """
                SELECT 
                    (SELECT COUNT(*) FROM news_raw) as total_raw,
                    (SELECT COUNT(*) FROM news_analyzed) as total_analyzed
            """;
            
            try (PreparedStatement stmt = conn.prepareStatement(countSql);
                 ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    long totalRaw = rs.getLong("total_raw");
                    long totalAnalyzed = rs.getLong("total_analyzed");
                    metrics.setTotalRawNews(totalRaw);
                    metrics.setTotalAnalyzed(totalAnalyzed);
                    
                    if (totalRaw > 0) {
                        metrics.setAnalysisCompletionRate((totalAnalyzed * 100.0) / totalRaw);
                    }
                }
            }

            // Sentiment distribution
            String sentimentSql = """
                SELECT 
                    SUM(CASE WHEN sentiment > 0.2 THEN 1 ELSE 0 END) as positive,
                    SUM(CASE WHEN sentiment < -0.2 THEN 1 ELSE 0 END) as negative,
                    SUM(CASE WHEN sentiment >= -0.2 AND sentiment <= 0.2 THEN 1 ELSE 0 END) as neutral
                FROM news_analyzed
            """;
            
            try (PreparedStatement stmt = conn.prepareStatement(sentimentSql);
                 ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    NewsSentimentMetrics.SentimentDistribution dist = 
                        new NewsSentimentMetrics.SentimentDistribution();
                    dist.setPositive(rs.getInt("positive"));
                    dist.setNegative(rs.getInt("negative"));
                    dist.setNeutral(rs.getInt("neutral"));
                    metrics.setSentimentDistribution(dist);
                }
            }

            // Top tickers by news volume
            List<NewsSentimentMetrics.TickerNewsVolume> topTickers = new ArrayList<>();
            String tickersSql = """
                SELECT 
                    ticker,
                    COUNT(*) as news_count
                FROM news_analyzed
                GROUP BY ticker
                ORDER BY news_count DESC
                LIMIT 10
            """;
            
            try (PreparedStatement stmt = conn.prepareStatement(tickersSql);
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    NewsSentimentMetrics.TickerNewsVolume volume = 
                        new NewsSentimentMetrics.TickerNewsVolume();
                    volume.setTicker(rs.getString("ticker"));
                    volume.setNewsCount(rs.getLong("news_count"));
                    topTickers.add(volume);
                }
            }
            metrics.setTopTickersByVolume(topTickers);
            
        } catch (Exception e) {
            LOG.error("Error calculating news sentiment metrics", e);
        }
        
        return metrics;
    }
}

