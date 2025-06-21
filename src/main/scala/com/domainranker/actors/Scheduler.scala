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

  private case class ScrapedReviews(reviews: List[TrustpilotReview]) extends Command

  private case class ProcessedReviews(reviews: List[TrustpilotReview]) extends Command

  private case class TrafficData(traffic: List[DomainTraffic]) extends Command

  private case class DomainSummaries(summaries: List[DomainSummary]) extends Command

  private case class RankedDomains(summaries: List[DomainSummary]) extends Command

  // Track the processing state to ensure proper flow
  private case class ProcessingState(
                                      scrapedReviews: Option[List[TrustpilotReview]] = None,
                                      processedReviews: Option[List[TrustpilotReview]] = None,
                                      trafficData: Option[List[DomainTraffic]] = None,
                                    )

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

    val dataStore = context.spawn(
      Behaviors.supervise(DomainDataStore())
        .onFailure(SupervisorStrategy.restart),
      "domain-data-store"
    )

    val ranker = context.spawn(
      Behaviors.supervise(Ranker())
        .onFailure(SupervisorStrategy.restart),
      "domain-ranker"
    )

    Behaviors.withTimers { timers =>
      // Set up periodic timers
      timers.startTimerWithFixedDelay(ScheduleFetchAndRank, FetchAndRankDomains, 0.seconds, rankingInterval)
      timers.startTimerWithFixedDelay(ScheduleResetReviews, ResetRecentReviewCounts, resetInterval, resetInterval)

      // Initialize behavior with empty processing state and the created actors
      schedulerBehavior(
        context,
        timers,
        trustpilotScraper,
        reviewProcessor,
        trafficFetcher,
        dataStore,
        ranker,
        ProcessingState()
      )
    }
  }

  private def schedulerBehavior(
                                 context: ActorContext[Command],
                                 timers: TimerScheduler[Command],
                                 trustpilotScraper: ActorRef[TrustpilotScraper.Command],
                                 reviewProcessor: ActorRef[ReviewProcessor.Command],
                                 trafficFetcher: ActorRef[TrafficFetcher.Command],
                                 dataStore: ActorRef[DomainDataStore.Command],
                                 ranker: ActorRef[Ranker.Command],
                                 state: ProcessingState
                               ): Behavior[Command] = {
    Behaviors.receiveMessage {
      case FetchAndRankDomains =>
        context.log.info("Starting fetch and rank process.")

        val reviewsResponseAdapter: ActorRef[List[TrustpilotReview]] =
          context.messageAdapter(reviews => ScrapedReviews(reviews))

        trustpilotScraper ! TrustpilotScraper.ScrapeAllCategoryLinks(reviewsResponseAdapter)

        Behaviors.same

      case ScrapedReviews(reviews) =>
        if (reviews.nonEmpty) {
          context.log.info(s"Received ${reviews.size} reviews from Trustpilot. Processing...")

          val domains = reviews.map(_.domain).toSet
          context.log.info(s"Found ${domains.size} unique domains: ${domains.mkString(", ")}")

          // Fetch traffic data for these domains
          val trafficAdapter: ActorRef[List[DomainTraffic]] =
            context.messageAdapter(traffic => TrafficData(traffic))

          trafficFetcher ! TrafficFetcher.FetchTraffic(domains, trafficAdapter)

          // Process reviews for sentiment analysis
          val processedReviewsAdapter: ActorRef[List[TrustpilotReview]] =
            context.messageAdapter(processedReviews => ProcessedReviews(processedReviews))

          reviewProcessor ! ReviewProcessor.ProcessReviews(reviews, processedReviewsAdapter)

          // Update state with scraped reviews
          schedulerBehavior(
            context, timers,
            trustpilotScraper, reviewProcessor, trafficFetcher, dataStore, ranker,
            state.copy(scrapedReviews = Some(reviews))
          )
        } else {
          context.log.warn("No reviews received from scraper!")
          Behaviors.same
        }

      case ProcessedReviews(reviews) =>
        context.log.info(s"Sentiment analysis completed for ${reviews.size} reviews.")

        // Add the processed reviews to the data store
        dataStore ! DomainDataStore.AddReviews(reviews)

        // Update state with processed reviews
        val newState = state.copy(processedReviews = Some(reviews))

        // If we have traffic data, request domain summaries for ranking
        if (newState.trafficData.isDefined) {
          context.log.info("Both review and traffic data available, requesting domain summaries...")
          requestDomainSummaries(context, dataStore, ranker)
        } else {
          context.log.info("Waiting for traffic data before ranking...")
        }

        schedulerBehavior(
          context, timers,
          trustpilotScraper, reviewProcessor, trafficFetcher, dataStore, ranker,
          newState
        )

      case TrafficData(traffic) =>
        context.log.info(s"Received traffic data for ${traffic.size} domains.")

        // Add traffic data to the data store
        dataStore ! DomainDataStore.AddTrafficData(traffic)

        // Update state with traffic data
        val newState = state.copy(trafficData = Some(traffic))

        // If we have processed reviews, request domain summaries for ranking
        if (newState.processedReviews.isDefined) {
          context.log.info("Both review and traffic data available, requesting domain summaries...")
          requestDomainSummaries(context, dataStore, ranker)
        } else {
          context.log.info("Waiting for processed reviews before ranking...")
        }

        schedulerBehavior(
          context, timers,
          trustpilotScraper, reviewProcessor, trafficFetcher, dataStore, ranker,
          newState
        )

      case DomainSummaries(summaries) =>
        context.log.info(s"Received ${summaries.size} domain summaries from data store.")

        if (summaries.nonEmpty) {
          // Request ranking
          val rankedSummariesAdapter: ActorRef[List[DomainSummary]] =
            context.messageAdapter(RankedDomains.apply)

          ranker ! Ranker.CalculateRanks(summaries, rankedSummariesAdapter)
        } else {
          context.log.warn("No domain summaries to rank!")
          // Reset state for next cycle
          schedulerBehavior(
            context, timers,
            trustpilotScraper, reviewProcessor, trafficFetcher, dataStore, ranker,
            ProcessingState()
          )
        }

        Behaviors.same

      case RankedDomains(summaries) =>
        // Log the ranked domains
        logRankedDomains(context, summaries)

        // Reset the processing state for the next cycle
        schedulerBehavior(
          context, timers,
          trustpilotScraper, reviewProcessor, trafficFetcher, dataStore, ranker,
          ProcessingState()
        )

      case ResetRecentReviewCounts =>
        context.log.info("Resetting recent review counts.")
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

  private def requestDomainSummaries(
                                      context: ActorContext[Command],
                                      dataStore: ActorRef[DomainDataStore.Command],
                                      ranker: ActorRef[Ranker.Command]
                                    ): Unit = {
    // Create a simpler adapter that just forwards the summaries to our actor
    val summariesAdapter: ActorRef[List[DomainSummary]] =
      context.messageAdapter(summaries => DomainSummaries(summaries))

    // Request domain summaries
    dataStore ! DomainDataStore.GetDomainSummaries(summariesAdapter)
  }

  private def logRankedDomains(context: ActorContext[Command], domains: List[DomainSummary]): Unit = {
    if (domains.isEmpty) {
      context.log.info("No ranked domains available yet.")
    } else {
      context.log.info("\nTOP RANKED DOMAINS:")
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