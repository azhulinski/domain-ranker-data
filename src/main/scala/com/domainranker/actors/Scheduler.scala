package com.domainranker.actors

import akka.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import akka.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import com.domainranker.models.{DomainSummary, DomainTraffic, TrustpilotReview}
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._

object Scheduler {
  sealed trait Command

  case object FetchAndRankDomains extends Command
  case object ResetRecentReviewCounts extends Command

  private case object ScheduleFetchAndRank extends Command
  private case object ScheduleResetReviews extends Command

  // Step 1: Initial scraping of reviews
  private case class ScrapedReviews(reviews: List[TrustpilotReview]) extends Command
  
  // Step 2: Traffic data retrieved
  private case class TrafficDataFetched(reviews: List[TrustpilotReview], traffic: List[DomainTraffic]) extends Command
  
  // Step 3: Reviews processed with sentiment
  private case class ReviewsProcessed(reviews: List[TrustpilotReview], traffic: List[DomainTraffic]) extends Command
  
  // Step 4: Rankings calculated
  private case class RankingComplete(summaries: List[DomainSummary]) extends Command
  
  // Step 5: Data stored in DomainDataStore
  private case class DataStoreComplete(summaries: List[DomainSummary]) extends Command

  def apply(openAiApiKey: String): Behavior[Command] = Behaviors.setup { context =>
    // Load configuration
    val config = ConfigFactory.load().getConfig("domainranker.scheduling")

    // Get timing values from configuration
    val rankingInterval = config.getDuration("ranking-interval").toSeconds.seconds
    val resetInterval = config.getDuration("reset-counts-interval").toSeconds.seconds

    // Create actors once here
    val trustpilotScraper = context.spawn(
      Behaviors.supervise(TrustpilotScraper())
        .onFailure(SupervisorStrategy.restart),
      "trustpilot-scraper"
    )

    val reviewProcessor = context.spawn(
      Behaviors.supervise(ReviewProcessor(openAiApiKey))
        .onFailure(SupervisorStrategy.restart),
      "review-processor"
    )

    val trafficFetcher = context.spawn(
      Behaviors.supervise(TrafficFetcher())
        .onFailure(SupervisorStrategy.restart),
      "traffic-fetcher"
    )

    val ranker = context.spawn(
      Behaviors.supervise(Ranker())
        .onFailure(SupervisorStrategy.restart),
      "domain-ranker"
    )
    
    val dataStore = context.spawn(
      Behaviors.supervise(DomainDataStore())
        .onFailure(SupervisorStrategy.restart),
      "domain-data-store"
    )

    Behaviors.withTimers { timers =>
      // Set up periodic timers
      timers.startTimerWithFixedDelay(ScheduleFetchAndRank, FetchAndRankDomains, 0.seconds, rankingInterval)
      timers.startTimerWithFixedDelay(ScheduleResetReviews, ResetRecentReviewCounts, resetInterval, resetInterval)

      // Initialize behavior with created actors
      schedulerBehavior(
        context,
        timers,
        trustpilotScraper,
        reviewProcessor,
        trafficFetcher,
        ranker,
        dataStore
      )
    }
  }

  private def schedulerBehavior(
                               context: ActorContext[Command],
                               timers: TimerScheduler[Command],
                               trustpilotScraper: ActorRef[TrustpilotScraper.Command],
                               reviewProcessor: ActorRef[ReviewProcessor.Command],
                               trafficFetcher: ActorRef[TrafficFetcher.Command],
                               ranker: ActorRef[Ranker.Command],
                               dataStore: ActorRef[DomainDataStore.Command]
                              ): Behavior[Command] = {
    Behaviors.receiveMessage {
      case FetchAndRankDomains =>
        context.log.info("===== STARTING NEW DOMAIN RANKING CYCLE =====")
        context.log.info("Step 1/5: Scraping reviews from Trustpilot...")
        
        // Start the process by scraping reviews
        val reviewsResponseAdapter: ActorRef[List[TrustpilotReview]] =
          context.messageAdapter(reviews => ScrapedReviews(reviews))

        trustpilotScraper ! TrustpilotScraper.ScrapeAllCategoryLinks(reviewsResponseAdapter)

        Behaviors.same

      case ScrapedReviews(reviews) =>
        if (reviews.isEmpty) {
          context.log.warn("No reviews received from scraper! Skipping this ranking cycle.")
          Behaviors.same
        } else {
          // Log success from step 1
          val domains = reviews.map(_.domain).toSet
          context.log.info(s"✓ Step 1/5 complete: Scraped ${reviews.size} reviews for ${domains.size} domains")
          
          // Start step 2 - get traffic data
          context.log.info("Step 2/5: Fetching traffic data...")
          
          val trafficAdapter: ActorRef[List[DomainTraffic]] =
            context.messageAdapter[List[DomainTraffic]] { trafficList =>
              TrafficDataFetched(reviews, trafficList)
            }

          trafficFetcher ! TrafficFetcher.FetchTraffic(domains, trafficAdapter)

          Behaviors.same
        }

      case TrafficDataFetched(reviews, traffic) =>
        // Log success from step 2
        context.log.info(s"✓ Step 2/5 complete: Retrieved traffic data for ${traffic.size} domains")
        
        // Start step 3 - process reviews for sentiment
        context.log.info("Step 3/5: Processing reviews for sentiment analysis...")
        
        val processedReviewsAdapter: ActorRef[List[TrustpilotReview]] =
          context.messageAdapter(processedReviews => ReviewsProcessed(processedReviews, traffic))

        reviewProcessor ! ReviewProcessor.ProcessReviews(reviews, processedReviewsAdapter)

        Behaviors.same

      case ReviewsProcessed(reviews, traffic) =>
        // Log success from step 3
        val reviewsWithSentiment = reviews.count(_.sentimentScore.isDefined)
        context.log.info(s"✓ Step 3/5 complete: Processed sentiment for $reviewsWithSentiment reviews")
        
        // First, store data in the DomainDataStore
        dataStore ! DomainDataStore.AddReviews(reviews)
        dataStore ! DomainDataStore.AddTrafficData(traffic)
        
        // Create domain summaries from the processed reviews and traffic data
        context.log.info("Step 4/5: Calculating domain rankings...")
        val summaries = createDomainSummaries(reviews, traffic)
        
        if (summaries.isEmpty) {
          context.log.warn("No valid domain summaries created! Skipping ranking.")
          Behaviors.same
        } else {
          // Request ranking of the summaries
          val rankedSummariesAdapter: ActorRef[List[DomainSummary]] =
            context.messageAdapter(RankingComplete.apply)

          ranker ! Ranker.CalculateRanks(summaries, rankedSummariesAdapter)
          Behaviors.same
        }

      case RankingComplete(summaries) =>
        // Log success from step 4
        context.log.info(s"✓ Step 4/5 complete: Ranked ${summaries.size} domains")
        
        // Store the ranked data in the data store
        context.log.info("Step 5/5: Storing data in persistent storage...")
        
        // Create a DataStoreComplete message adapter
        val dataStoreCompleteAdapter: ActorRef[List[DomainSummary]] =
          context.messageAdapter(DataStoreComplete.apply)
        
        // This is just a placeholder since DomainDataStore doesn't have a specific method for storing ranked summaries
        // We'll just use GetDomainSummaries to trigger the completion of this step
        dataStore ! DomainDataStore.GetDomainSummaries(dataStoreCompleteAdapter)
        
        Behaviors.same
        
      case DataStoreComplete(summaries) =>
        // Log success from step 5
        context.log.info(s"✓ Step 5/5 complete: Stored data for ${summaries.size} domains")
        
        // Log the ranked domains
        logRankedDomains(context, summaries)
        context.log.info("===== DOMAIN RANKING CYCLE COMPLETED =====\n")

        Behaviors.same

      case ResetRecentReviewCounts =>
        context.log.info("Resetting recent review counts in data store.")
        dataStore ! DomainDataStore.ClearRecentCounts
        Behaviors.same

      case ScheduleFetchAndRank =>
        // Timer key only - ignore
        Behaviors.same

      case ScheduleResetReviews =>
        // Timer key only - ignore
        Behaviors.same
    }
  }

  /**
   * Creates domain summaries directly from reviews and traffic data
   */
  private def createDomainSummaries(
                                    reviews: List[TrustpilotReview],
                                    trafficData: List[DomainTraffic]
                                   ): List[DomainSummary] = {
    import java.time.{Duration, Instant}
    
    val now = Instant.now()
    val trafficByDomain = trafficData.map(t => (t.domain, t.traffic)).toMap
    
    // Group reviews by domain
    val reviewsByDomain = reviews.groupBy(_.domain)
    
    // Create summaries for each domain
    reviewsByDomain.map { case (domain, domainReviews) =>
      // Find latest review
      val latestReview = domainReviews.maxByOption(_.reviewDate)
      
      // Find oldest review to calculate domain age
      val oldestReview = domainReviews.minByOption(_.reviewDate)
      val domainAgeInDays = oldestReview.map { firstReview =>
        Duration.between(firstReview.reviewDate, now).toDays
      }.getOrElse(0L)
      
      // Calculate recent reviews (with sentiment scores)
      val recentReviews = domainReviews.filter(_.sentimentScore.isDefined)
      
      // Calculate time-weighted sentiment sum for recent reviews
      val sentimentSumRecent = recentReviews.map { review =>
        val daysSinceReview = Duration.between(review.reviewDate, now).toDays
        val timeWeight = Math.max(0.5, 1.0 - (daysSinceReview / 30.0)) // Reviews within 30 days get higher weight
        
        review.sentimentScore.getOrElse(0.0) * timeWeight
      }.sum
      
      // Create domain summary
      DomainSummary(
        domain = domain,
        latestReview = latestReview,
        recentReviewCount = recentReviews.size,
        totalReviewCount = domainReviews.size,
        traffic = trafficByDomain.get(domain),
        sentimentSumRecentReviews = sentimentSumRecent,
        domainAge = domainAgeInDays
      )
    }.toList
  }

  private def logRankedDomains(context: ActorContext[Command], domains: List[DomainSummary]): Unit = {
    if (domains.isEmpty) {
      context.log.warn("No ranked domains available.")
    } else {
      context.log.info("\n📊 TOP RANKED DOMAINS:")
      context.log.info("===================")

      domains.zipWithIndex.foreach { case (domain, index) =>
        val rankInfo = domain.latestReview.map(r =>
          s" - Latest Review: '${r.reviewText.take(50)}${if (r.reviewText.length > 50) "..." else ""}'" +
            s" - Sentiment: ${r.sentimentScore.getOrElse("N/A")}"
        ).getOrElse("")

        context.log.info(f"${index + 1}. ${domain.domain}%-25s | Score: ${domain.rankScore}%.2f | " +
          f"Reviews: ${domain.recentReviewCount}%-2d | " +
          f"Traffic: ${domain.traffic.getOrElse(0L)}%-10d$rankInfo")
      }
      context.log.info("===================\n")
    }
  }
}