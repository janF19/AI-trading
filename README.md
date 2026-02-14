# AI Trading Analyzer

## About the Project

A microservice built on the Micronaut framework that uses Kafka for data processing.

## What the Service Does

The service collects news data and uses LLM API to automatically determine:
- **News sentiment** (positive/negative/neutral)
- **Stock ticker** that the news relates to

It then retrieves current price data for the given stock and evaluates price movement in relation to the news.

## Technologies

- **Micronaut** - microservices framework
- **Kafka** - messaging and data processing
- **LLM API** - sentiment analysis and stock identification
- **Dashboard** - simple web dashboard for displaying results


 **Project requires API keys:**
- LLM API key (e.g., Google)
- API key for retrieving stock prices

Due to the use of free APIs, there is no emphasis on fast response times - the service primarily serves to collect and analyze data.

## Configuration

The project requires the following API keys. Set them as **environment variables**:

```bash
# Google Gemini API for sentiment analysis
export GEMINI_API_KEY=your-key-here

# API for retrieving stock prices (one is sufficient)
export TWELVEDATA_API_KEY=your-key-here
export ALPHAVANTAGE_API_KEY=your-key-here
export FINNHUB_API_KEY=your-key-here

# Database (optional, default values work with docker-compose)
export CLICKHOUSE_USER=default
export CLICKHOUSE_PASSWORD=clickhouse123
```

### How to obtain API keys:
- **Gemini**: https://makersuite.google.com/app/apikey
- **TwelveData**: https://twelvedata.com/
- **Alpha Vantage**: https://www.alphavantage.co/support/#api-key
- **Finnhub**: https://finnhub.io/

## Running

1. Set environment variables (see Configuration section)
2. Start Kafka and ClickHouse: `docker-compose up -d`
3. Run the application: `./mvnw mn:run`
4. Dashboard is available at: `http://localhost:8083/analytics/dashboard`

