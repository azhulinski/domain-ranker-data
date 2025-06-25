package com.domainranker.actors

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import com.domainranker.models.{DomainSummary, DomainTraffic, TrustpilotReview}
import com.typesafe.config.ConfigFactory
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.duration._

object Scheduler {
  private val logger: Logger = LoggerFactory.getLogger(Scheduler.getClass)

  sealed trait Command

  private case object FetchAndRankDomains extends Command

  private case object ResetRecentReviewCounts extends Command

  private case object ScheduleFetchAndRank extends Command

  private case object ScheduleResetReviews extends Command

  private case class ScrapedReviews(reviews: List[TrustpilotReview]) extends Command

  private case class TrafficDataFetched(reviews: List[TrustpilotReview], traffic: List[DomainTraffic]) extends Command

  private case class ReviewsProcessed(reviews: List[TrustpilotReview], traffic: List[DomainTraffic]) extends Command

  private case class RankingComplete(summaries: List[DomainSummary]) extends Command

  private case class DataStoreComplete(summaries: List[DomainSummary]) extends Command

  def apply(openAiApiKey: String): Behavior[Command] = Behaviors.setup { context =>
    logger.info("Initializing Domain Ranker Scheduler")

    val config = ConfigFactory.load().getConfig("domainranker.scheduling")

    val rankingInterval = config.getDuration("ranking-interval").toSeconds.seconds
    val resetInterval = config.getDuration("reset-counts-interval").toSeconds.seconds

    logger.info(s"Configured ranking interval: ${rankingInterval.toSeconds} seconds")
    logger.info(s"Configured reset interval: ${resetInterval.toSeconds} seconds")

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

    logger.info("All actors created, setting up timers")

    Behaviors.withTimers { timers =>
      logger.info("Starting ranking and reset timers")
      timers.startTimerWithFixedDelay(ScheduleFetchAndRank, FetchAndRankDomains, 0.seconds, rankingInterval)
      timers.startTimerWithFixedDelay(ScheduleResetReviews, ResetRecentReviewCounts, resetInterval, resetInterval)

      schedulerBehavior(
        context,
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
                                 trustpilotScraper: ActorRef[TrustpilotScraper.Command],
                                 reviewProcessor: ActorRef[ReviewProcessor.Command],
                                 trafficFetcher: ActorRef[TrafficFetcher.Command],
                                 ranker: ActorRef[Ranker.Command],
                                 dataStore: ActorRef[DomainDataStore.Command]
                               ): Behavior[Command] = {
    Behaviors.receiveMessage {
      case FetchAndRankDomains =>
        logger.info("===== STARTING NEW DOMAIN RANKING CYCLE =====")
        logger.info("Step 1/5: Scraping reviews from Trustpilot...")

        val reviewsResponseAdapter: ActorRef[List[TrustpilotReview]] =
          context.messageAdapter(reviews => ScrapedReviews(reviews))

        trustpilotScraper ! TrustpilotScraper.ScrapeAllCategoryLinks(reviewsResponseAdapter)

        Behaviors.same

      case ScrapedReviews(reviews) =>
        if (reviews.isEmpty) {
          logger.warn("No reviews received from scraper! Skipping this ranking cycle.")
          Behaviors.same
        } else {
          val domains = reviews.map(_.domain).toSet
          logger.info(s"✓ Step 1/5 complete: Scraped ${reviews.size} reviews for ${domains.size} domains")

          logger.info("Step 2/5: Fetching traffic data...")

          val trafficAdapter: ActorRef[List[DomainTraffic]] =
            context.messageAdapter[List[DomainTraffic]] { trafficList =>
              TrafficDataFetched(reviews, trafficList)
            }

          trafficFetcher ! TrafficFetcher.FetchTraffic(domains, trafficAdapter)

          Behaviors.same
        }

      case TrafficDataFetched(reviews, traffic) =>
        logger.info(s"✓ Step 2/5 complete: Retrieved traffic data for ${traffic.size} domains")

        logger.info("Step 3/5: Processing reviews for sentiment analysis...")

        val processedReviewsAdapter: ActorRef[List[TrustpilotReview]] =
          context.messageAdapter(processedReviews => ReviewsProcessed(processedReviews, traffic))

        reviewProcessor ! ReviewProcessor.ProcessReviews(reviews, processedReviewsAdapter)

        Behaviors.same

      case ReviewsProcessed(reviews, traffic) =>
        val reviewsWithSentiment = reviews.count(_.sentimentScore.isDefined)
        logger.info(s"✓ Step 3/5 complete: Processed sentiment for $reviewsWithSentiment reviews")

        dataStore ! DomainDataStore.AddReviews(reviews)
        dataStore ! DomainDataStore.AddTrafficData(traffic)

        logger.info("Step 4/5: Calculating domain rankings...")
        val summaries = createDomainSummaries(reviews, traffic)

        if (summaries.isEmpty) {
          logger.warn("No valid domain summaries created! Skipping ranking.")
          Behaviors.same
        } else {
          val rankedSummariesAdapter: ActorRef[List[DomainSummary]] =
            context.messageAdapter(RankingComplete.apply)

          ranker ! Ranker.CalculateRanks(summaries, rankedSummariesAdapter)
          Behaviors.same
        }

      case RankingComplete(summaries) =>
        logger.info(s"✓ Step 4/5 complete: Ranked ${summaries.size} domains")

        logger.info("Step 5/5: Storing data in persistent storage...")

        val dataStoreCompleteAdapter: ActorRef[List[DomainSummary]] =
          context.messageAdapter(DataStoreComplete.apply)

        dataStore ! DomainDataStore.GetDomainSummaries(dataStoreCompleteAdapter)

        Behaviors.same

      case DataStoreComplete(summaries) =>
        logger.info(s"✓ Step 5/5 complete: Stored data for ${summaries.size} domains")

        logRankedDomains(summaries)
        logger.info("===== DOMAIN RANKING CYCLE COMPLETED =====\n")

        Behaviors.same

      case ResetRecentReviewCounts =>
        logger.info("Resetting recent review counts in data store.")
        dataStore ! DomainDataStore.ClearRecentCounts
        Behaviors.same
    }
  }

  private def createDomainSummaries(
                                     reviews: List[TrustpilotReview],
                                     trafficData: List[DomainTraffic]
                                   ): List[DomainSummary] = {
    logger.debug(s"Creating domain summaries from ${reviews.size} reviews and ${trafficData.size} traffic data points")

    val trafficByDomain = trafficData.map(t => (t.domain, t.traffic)).toMap

    val reviewsByDomain = reviews.groupBy(_.domain)

    reviewsByDomain.map { case (domain, domainReviews) =>
      val latestReview = domainReviews.maxByOption(_.reviewDate)
      val recentReviews = domainReviews.filter(_.sentimentScore.isDefined)

      val sentimentSumRecent = recentReviews.flatMap(_.sentimentScore).sum

      logger.debug(s"Domain $domain: ${recentReviews.size} recent reviews, sentiment sum: $sentimentSumRecent")

      DomainSummary(
        domain = domain,
        latestReview = latestReview,
        recentReviewCount = recentReviews.size,
        totalReviewCount = domainReviews.size,
        traffic = trafficByDomain.get(domain),
        sentimentSumRecentReviews = sentimentSumRecent
      )
    }.toList
  }

  private def logRankedDomains(domains: List[DomainSummary]): Unit = {
    if (domains.isEmpty) {
      logger.warn("No ranked domains available.")
    } else {
      logger.info("\n📊 TOP RANKED DOMAINS:")
      logger.info("===================")

      domains.zipWithIndex.foreach { case (domain, index) =>
        val rankInfo = domain.latestReview.map(r =>
          s" - Latest Review: '${r.reviewText.take(50)}${if (r.reviewText.length > 50) "..." else ""}'" +
            s" - Sentiment: ${r.sentimentScore.getOrElse("N/A")}"
        ).getOrElse("")

        logger.info(f"${index + 1}. ${domain.domain}%-25s | " +
          f"Recent Reviews: ${domain.recentReviewCount}%-2d | " +
          f"Sentiment Sum: ${domain.sentimentSumRecentReviews}%.2f | " +
          f"Total Reviews: ${domain.totalReviewCount}%-3d | " +
          f"Traffic: ${domain.traffic.getOrElse(0L)}%-10d$rankInfo")
      }
      logger.info("===================\n")
    }
  }
}