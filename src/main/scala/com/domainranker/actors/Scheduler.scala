package com.domainranker.actors

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import com.domainranker.models.{DomainSummary, DomainTraffic, TrustpilotReview}
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._

object Scheduler {
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
    val config = ConfigFactory.load().getConfig("domainranker.scheduling")

    val rankingInterval = config.getDuration("ranking-interval").toSeconds.seconds
    val resetInterval = config.getDuration("reset-counts-interval").toSeconds.seconds

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
        context.log.info("===== STARTING NEW DOMAIN RANKING CYCLE =====")
        context.log.info("Step 1/5: Scraping reviews from Trustpilot...")

        val reviewsResponseAdapter: ActorRef[List[TrustpilotReview]] =
          context.messageAdapter(reviews => ScrapedReviews(reviews))

        trustpilotScraper ! TrustpilotScraper.ScrapeAllCategoryLinks(reviewsResponseAdapter)

        Behaviors.same

      case ScrapedReviews(reviews) =>
        if (reviews.isEmpty) {
          context.log.warn("No reviews received from scraper! Skipping this ranking cycle.")
          Behaviors.same
        } else {

          val domains = reviews.map(_.domain).toSet
          context.log.info(s"✓ Step 1/5 complete: Scraped ${reviews.size} reviews for ${domains.size} domains")

          context.log.info("Step 2/5: Fetching traffic data...")

          val trafficAdapter: ActorRef[List[DomainTraffic]] =
            context.messageAdapter[List[DomainTraffic]] { trafficList =>
              TrafficDataFetched(reviews, trafficList)
            }

          trafficFetcher ! TrafficFetcher.FetchTraffic(domains, trafficAdapter)

          Behaviors.same
        }

      case TrafficDataFetched(reviews, traffic) =>

        context.log.info(s"✓ Step 2/5 complete: Retrieved traffic data for ${traffic.size} domains")

        context.log.info("Step 3/5: Processing reviews for sentiment analysis...")

        val processedReviewsAdapter: ActorRef[List[TrustpilotReview]] =
          context.messageAdapter(processedReviews => ReviewsProcessed(processedReviews, traffic))

        reviewProcessor ! ReviewProcessor.ProcessReviews(reviews, processedReviewsAdapter)

        Behaviors.same

      case ReviewsProcessed(reviews, traffic) =>

        val reviewsWithSentiment = reviews.count(_.sentimentScore.isDefined)
        context.log.info(s"✓ Step 3/5 complete: Processed sentiment for $reviewsWithSentiment reviews")

        dataStore ! DomainDataStore.AddReviews(reviews)
        dataStore ! DomainDataStore.AddTrafficData(traffic)

        context.log.info("Step 4/5: Calculating domain rankings...")
        val summaries = createDomainSummaries(reviews, traffic)

        if (summaries.isEmpty) {
          context.log.warn("No valid domain summaries created! Skipping ranking.")
          Behaviors.same
        } else {

          val rankedSummariesAdapter: ActorRef[List[DomainSummary]] =
            context.messageAdapter(RankingComplete.apply)

          ranker ! Ranker.CalculateRanks(summaries, rankedSummariesAdapter)
          Behaviors.same
        }

      case RankingComplete(summaries) =>

        context.log.info(s"✓ Step 4/5 complete: Ranked ${summaries.size} domains")

        context.log.info("Step 5/5: Storing data in persistent storage...")

        val dataStoreCompleteAdapter: ActorRef[List[DomainSummary]] =
          context.messageAdapter(DataStoreComplete.apply)

        dataStore ! DomainDataStore.GetDomainSummaries(dataStoreCompleteAdapter)

        Behaviors.same

      case DataStoreComplete(summaries) =>

        context.log.info(s"✓ Step 5/5 complete: Stored data for ${summaries.size} domains")

        logRankedDomains(context, summaries)
        context.log.info("===== DOMAIN RANKING CYCLE COMPLETED =====\n")

        Behaviors.same

      case ResetRecentReviewCounts =>
        context.log.info("Resetting recent review counts in data store.")
        dataStore ! DomainDataStore.ClearRecentCounts
        Behaviors.same

      case ScheduleFetchAndRank =>

        Behaviors.same

      case ScheduleResetReviews =>

        Behaviors.same
    }
  }

  private def createDomainSummaries(
                                     reviews: List[TrustpilotReview],
                                     trafficData: List[DomainTraffic]
                                   ): List[DomainSummary] = {

    val trafficByDomain = trafficData.map(t => (t.domain, t.traffic)).toMap

    val reviewsByDomain = reviews.groupBy(_.domain)

    reviewsByDomain.map { case (domain, domainReviews) =>
      // Get the latest review for the domain (only 1, as per requirements)
      val latestReview = domainReviews.maxByOption(_.reviewDate)

      // Count all reviews with sentiment scores
      val recentReviews = domainReviews.filter(_.sentimentScore.isDefined)

      // Calculate sum of sentiment scores for all recent reviews
      val sentimentSumRecent = recentReviews.flatMap(_.sentimentScore).sum

      DomainSummary(
        domain = domain,
        latestReview = latestReview,
        recentReviewCount = recentReviews.size,
        totalReviewCount = domainReviews.size,
        traffic = trafficByDomain.get(domain),
        sentimentSumRecentReviews = sentimentSumRecent,
        domainAge = 0 // This field is no longer needed for ranking
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

        context.log.info(f"${index + 1}. ${domain.domain}%-25s | " +
          f"Recent Reviews: ${domain.recentReviewCount}%-2d | " +
          f"Sentiment Sum: ${domain.sentimentSumRecentReviews}%.2f | " +
          f"Total Reviews: ${domain.totalReviewCount}%-3d | " +
          f"Traffic: ${domain.traffic.getOrElse(0L)}%-10d$rankInfo")
      }
      context.log.info("===================\n")
    }
  }
}