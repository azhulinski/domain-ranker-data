package com.domainranker.actors

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import com.domainranker.models.{DomainSummary, DomainTraffic, TrustpilotReview}

import java.time.{Duration, Instant}
import scala.collection.mutable.{Map => MMap, Set => MSet}

object DomainDataStore {
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
        context.log.info(s"Adding ${reviews.size} reviews to data store.")
        
        // Group reviews by domain for more efficient processing
        val reviewsByDomain = reviews.groupBy(_.domain)
        
        reviewsByDomain.foreach { case (domain, domainReviews) =>
          val current = domainStates.getOrElseUpdate(
            domain, 
            DomainState(None, None, MSet.empty, 0, None)
          )

          val newTotalCount = current.totalReviewCount + domainReviews.size
          
          // Find newest review for this domain
          val newestReview = domainReviews.maxByOption(_.reviewDate)
          
          // Update latest review if needed
          val newLatestReview = (current.latestReview, newestReview) match {
            case (Some(existing), Some(newReview)) if newReview.reviewDate.isAfter(existing.reviewDate) => 
              Some(newReview)
            case (None, Some(newReview)) => 
              Some(newReview)
            case _ => 
              current.latestReview
          }
          
          // Find oldest review to track domain age
          val oldestReview = domainReviews.minByOption(_.reviewDate)
          
          // Update first review date if needed
          val newFirstReviewDate = (current.firstReviewDate, oldestReview) match {
            case (Some(existing), Some(oldReview)) if oldReview.reviewDate.isBefore(existing) => 
              Some(oldReview.reviewDate)
            case (None, Some(oldReview)) => 
              Some(oldReview.reviewDate)
            case _ => 
              current.firstReviewDate
          }
          
          // Add reviews with sentiment scores to recent reviews set
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
        context.log.info(s"Adding ${trafficData.size} traffic entries to data store.")
        trafficData.foreach { data =>
          val current = domainStates.getOrElseUpdate(
            data.domain, 
            DomainState(None, None, MSet.empty, 0, None)
          )
          domainStates.update(data.domain, current.copy(traffic = Some(data.traffic)))
        }
        Behaviors.same

      case GetDomainSummaries(replyTo) =>
        context.log.info("Generating domain summaries.")
        val now = Instant.now()
        
        val summaries = domainStates.map { case (domain, state) =>
          // Calculate time-weighted sentiment sum for recent reviews
          val sentimentSumRecent = state.recentReviews.map { review =>
            val daysSinceReview = Duration.between(review.reviewDate, now).toDays
            val timeWeight = Math.max(0.5, 1.0 - (daysSinceReview / 30.0)) // Reviews within 30 days get higher weight
            
            review.sentimentScore.getOrElse(0.0) * timeWeight
          }.sum
          
          // Calculate domain age in days (if available)
          val domainAgeInDays = state.firstReviewDate.map { firstDate =>
            Duration.between(firstDate, now).toDays
          }.getOrElse(0L)
          
          // Create domain summary with age information
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
        context.log.info("Clearing recent review counts.")
        domainStates.values.foreach(_.recentReviews.clear())
        Behaviors.same
    }
  }
}