package com.domainranker.actors

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import com.domainranker.models.{DomainSummary, DomainTraffic, TrustpilotReview}
import org.slf4j.{Logger, LoggerFactory}

import java.time.{Duration, Instant}
import scala.collection.mutable.{Map => MMap, Set => MSet}

object DomainDataStore {
  private val logger: Logger = LoggerFactory.getLogger(DomainDataStore.getClass)

  sealed trait Command

  case class AddReviews(reviews: List[TrustpilotReview]) extends Command
  case class AddTrafficData(trafficData: List[DomainTraffic]) extends Command
  case class GetDomainSummaries(replyTo: ActorRef[List[DomainSummary]]) extends Command
  case object ClearRecentCounts extends Command

  private case class DomainState(
    latestReview: Option[TrustpilotReview],
    firstReviewDate: Option[Instant],
    recentReviews: MSet[TrustpilotReview],
    totalReviewCount: Int,
    traffic: Option[Long]
  )

  def apply(): Behavior[Command] = Behaviors.setup { context =>
    val domainStates: MMap[String, DomainState] = MMap.empty

    Behaviors.receiveMessage {
      case AddReviews(reviews) =>
        logger.info(s"Adding ${reviews.size} reviews to data store.")
        
        val reviewsByDomain = reviews.groupBy(_.domain)
        
        reviewsByDomain.foreach { case (domain, domainReviews) =>
          val current = domainStates.getOrElseUpdate(
            domain, 
            DomainState(None, None, MSet.empty[TrustpilotReview], 0, None)
          )

          val newTotalCount = current.totalReviewCount + domainReviews.size
          val newestReview = domainReviews.maxByOption(_.reviewDate)
          
          val newLatestReview = (current.latestReview, newestReview) match {
            case (Some(existing), Some(newReview)) if newReview.reviewDate.isAfter(existing.reviewDate) => 
              Some(newReview)
            case (None, Some(newReview)) => 
              Some(newReview)
            case _ => 
              current.latestReview
          }
          
          val oldestReview = domainReviews.minByOption(_.reviewDate)
          
          val newFirstReviewDate = (current.firstReviewDate, oldestReview) match {
            case (Some(existing), Some(oldReview)) if oldReview.reviewDate.isBefore(existing) => 
              Some(oldReview.reviewDate)
            case (None, Some(oldReview)) => 
              Some(oldReview.reviewDate)
            case _ => 
              current.firstReviewDate
          }
          
          domainReviews.foreach { review =>
            if (review.sentimentScore.isDefined && !current.recentReviews.exists(_.id == review.id)) {
              current.recentReviews += review
            }
          }

          domainStates.update(domain, current.copy(
            latestReview = newLatestReview,
            firstReviewDate = newFirstReviewDate,
            totalReviewCount = newTotalCount
          ))
        }
        Behaviors.same

      case AddTrafficData(trafficData) =>
        logger.info(s"Adding ${trafficData.size} traffic entries to data store.")
        
        trafficData.foreach { data =>
          val domainName: String = data.domain
          val trafficValue: Long = data.traffic
          
          val current = domainStates.get(domainName) match {
            case Some(state) => 
              
              domainStates.update(domainName, state.copy(traffic = Some(trafficValue)))
            case None =>
              
              domainStates.put(
                domainName, 
                DomainState(None, None, MSet.empty[TrustpilotReview], 0, Some(trafficValue))
              )
          }
        }
        Behaviors.same

      case GetDomainSummaries(replyTo) =>
        logger.info("Generating domain summaries.")
        val now = Instant.now()
        
        val summaries = domainStates.map { case (domain, state) =>
          
          val sentimentSumRecent = state.recentReviews.map { review =>
            val daysSinceReview = Duration.between(review.reviewDate, now).toDays
            val timeWeight = Math.max(0.5, 1.0 - (daysSinceReview / 30.0))
            
            review.sentimentScore.getOrElse(0.0) * timeWeight
          }.sum
          
          val domainAgeInDays = state.firstReviewDate.map { firstDate =>
            Duration.between(firstDate, now).toDays
          }.getOrElse(0L)
          
          DomainSummary(
            domain = domain,
            latestReview = state.latestReview,
            recentReviewCount = state.recentReviews.size,
            totalReviewCount = state.totalReviewCount,
            traffic = state.traffic,
            sentimentSumRecentReviews = sentimentSumRecent,
            domainAge = domainAgeInDays
          )
        }.toList
        
        replyTo ! summaries
        Behaviors.same

      case ClearRecentCounts =>
        logger.info("Clearing recent review counts.")
        domainStates.values.foreach(_.recentReviews.clear())
        Behaviors.same
    }
  }
}