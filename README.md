# AI Trading Analyzer

## O projektu

Mikroslužba postavená nad frameworkem Micronaut, která využívá Kafku pro zpracování dat.

## Co služba dělá

Služba sbírá data o zprávách a pomocí LLM API automaticky určuje:
- **Sentiment zprávy** (pozitivní/negativní/neutrální)
- **Akciový titul**, kterého se zpráva týká

Následně získává aktuální data o ceně dané akcie a vyhodnocuje pohyb ceny v souvislosti se zprávou.

## Technologie

- **Micronaut** - framework pro mikroslužby
- **Kafka** - messaging a zpracování dat
- **LLM API** - analýza sentimentu a identifikace akcií
- **Dashboard** - jednoduchý webový dashboard pro zobrazení výsledků


 **Projekt vyžaduje API klíče:**
- LLM API klíč (např. Google)
- API klíč pro získání cen akcií

Vzhledem k využití bezplatných API není důraz na rychlou odezvu - služba primárně slouží ke sběru a analýze dat.

## Konfigurace

Projekt vyžaduje následující API klíče. Nastavte je jako **environment variables**:

```bash
# Google Gemini API pro analýzu sentimentu
export GEMINI_API_KEY=your-key-here

# API pro získání cen akcií (stačí jeden)
export TWELVEDATA_API_KEY=your-key-here
export ALPHAVANTAGE_API_KEY=your-key-here
export FINNHUB_API_KEY=your-key-here

# Database (volitelné, výchozí hodnoty fungují s docker-compose)
export CLICKHOUSE_USER=default
export CLICKHOUSE_PASSWORD=clickhouse123
```

### Jak získat API klíče:
- **Gemini**: https://makersuite.google.com/app/apikey
- **TwelveData**: https://twelvedata.com/
- **Alpha Vantage**: https://www.alphavantage.co/support/#api-key
- **Finnhub**: https://finnhub.io/

## Spuštění

1. Nastavte environment variables (viz sekce Konfigurace)
2. Spusťte Kafka a ClickHouse: `docker-compose up -d`
3. Spusťte aplikaci: `./mvnw mn:run`
4. Dashboard je dostupný na: `http://localhost:8083/analytics/dashboard`

