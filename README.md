# DomainRanker

DomainRanker is a Scala-based service that automatically scrapes, analyzes, and ranks domains based on multiple factors including user reviews, sentiment analysis, traffic data, and domain age.

## Overview

DomainRanker follows a 5-step process to produce high-quality domain rankings:

1. **Review Scraping**: Collects recent reviews from Trustpilot across various categories
2. **Traffic Analysis**: Fetches traffic data for each domain
3. **Sentiment Analysis**: Uses OpenAI's GPT models to analyze review sentiment
4. **Ranking Calculation**: Ranks domains based on multiple weighted factors
5. **Data Storage**: Maintains persistent storage of domain data and rankings

The system uses Akka actors to process data in a resilient, concurrent fashion with a clean step-by-step workflow.

## Architecture

DomainRanker is built on the following components:

- **Akka Typed Actors**: For concurrent, message-driven processing
- **Trustpilot Scraper**: Extracts review data from Trustpilot
- **OpenAI Integration**: Performs sentiment analysis on reviews
- **Domain Data Store**: Maintains domain data across processing cycles

## Key Features

- **Multi-factor Ranking**: Combines traffic, review counts, sentiment, and recency
- **Sentiment Analysis**: AI-powered scoring of review sentiments
- **Time-weighted Analysis**: Prioritizes recent content over older data
- **Periodic Updates**: Configurable schedule for fetching fresh data
- **Resilient Design**: Automatic recovery from failures through supervision

## Requirements

- Java 11 or higher
- SBT (Scala Build Tool)
- OpenAI API key

## Configuration

Create an `application.conf` file in your `src/main/resources` directory with the following structure:

Replace `YOUR_OPENAI_API_KEY_HERE` with your actual OpenAI API key. Adjust the weights and intervals as needed.

## Running the Application

### Using SBT

1. Clone the repository:
   ```
   git clone https://github.com/yourusername/domainranker.git
   cd domainranker
   ```

2. Set your OpenAI API key in `src/main/resources/application.conf`

3. Run the application:
   ```
   sbt run
   ```

### Using JAR file

1. Build the package:
   ```
   sbt assembly
   ```

2. Run the generated JAR:
   ```
   java -jar target/scala-2.13/DomainRankerData-assembly-0.1.0-SNAPSHOT.jar
   ```

## Output

The application logs the ranking process through each step and ultimately produces a ranked list of domains with their scores, review counts, traffic estimates, and sentiment scores.

Example output:

## Development

### Project Structure

- `Main.scala`: Application entry point
- `actors/`: Actor implementations
  - `Scheduler.scala`: Coordinates the entire ranking process
  - `TrustpilotScraper.scala`: Extracts data from Trustpilot
  - `TrafficFetcher.scala`: Obtains traffic estimates
  - `ReviewProcessor.scala`: Performs sentiment analysis using OpenAI
  - `Ranker.scala`: Calculates domain rankings
  - `DomainDataStore.scala`: Persists domain information
- `models/`: Data models for the application

### Building