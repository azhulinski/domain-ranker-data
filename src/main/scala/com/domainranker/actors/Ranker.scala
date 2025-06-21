package com.domainranker.actors

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import com.domainranker.models.DomainSummary
import com.typesafe.config.ConfigFactory

import java.time.{Duration, Instant}
import scala.collection.mutable
import scala.collection.mutable.{Map => MMap}

object Ranker {
  sealed trait Command

  case class CalculateRanks(summaries: List[DomainSummary], replyTo: ActorRef[List[DomainSummary]]) extends Command

  // Default category weights - could be moved to configuration
  private val categoryWeights: MMap[String, Double] = mutable.Map(
    "electronics" -> 1.2,
    "software" -> 1.3,
    "shopping" -> 1.1,
    "entertainment" -> 0.9,
    "finance" -> 1.2,
    "travel" -> 1.0,
    "health" -> 1.1
    // Add more categories as needed
  ).withDefaultValue(1.0) // Default weight for unknown categories
  
  def apply(): Behavior[Command] = Behaviors.setup { context =>
    // Load configuration
    val config = ConfigFactory.load().getConfig("domainranker.ranking")
    
    // Get weights from configuration
    val TRAFFIC_WEIGHT = config.getDouble("traffic-weight")
    val REVIEW_COUNT_WEIGHT = config.getDouble("review-count-weight")
    val SENTIMENT_WEIGHT = config.getDouble("sentiment-weight")
    val RECENCY_WEIGHT = config.getDouble("recency-weight")
    val CATEGORY_WEIGHT = 0.1 // Can be moved to config
    
    Behaviors.receiveMessage {
      case CalculateRanks(summaries, replyTo) =>
        context.log.info(s"Calculating ranks for ${summaries.size} domains.")
        
        // Find max values for normalization
        val maxTraffic = summaries.flatMap(_.traffic).maxOption.getOrElse(1L).toDouble
        val maxReviewCount = summaries.map(_.recentReviewCount).maxOption.getOrElse(1).toDouble
        val maxTotalReviewCount = summaries.map(_.totalReviewCount).maxOption.getOrElse(1).toDouble
        
        val now = Instant.now()
        
        val rankedSummaries = summaries
          .map { summary =>
            // 1. Calculate normalized traffic score (0 to 1)
            val trafficScore = summary.traffic match {
              case Some(traffic) => (traffic.toDouble / maxTraffic)
              case None => 0.0
            }
            
            // 2. Calculate normalized review count score (0 to 1)
            val reviewCountScore = if (maxReviewCount > 0) {
              summary.recentReviewCount.toDouble / maxReviewCount
            } else 0.0
            
            // 3. Calculate average sentiment (not sum) and normalize to 0-1
            val sentimentScore = {
              if (summary.recentReviewCount > 0) {
                val avgSentiment = summary.sentimentSumRecentReviews / summary.recentReviewCount.toDouble
                (avgSentiment + 1.0) / 2.0 // Convert from -1..1 to 0..1
              } else 0.5 // Neutral if no reviews
            }
            
            // 4. Calculate recency score with time decay
            val recencyScore = summary.latestReview.map { review =>
              val daysSinceReview = Duration.between(review.reviewDate, now).toDays
              Math.max(0.0, 1.0 - (daysSinceReview / 30.0)) // Decay over 30 days
            }.getOrElse(0.0)
            
            // 5. Calculate category score based on domain's category
            val categoryScore = summary.latestReview
              .map(review => categoryWeights(review.category.toLowerCase))
              .getOrElse(1.0)
            
            // 6. Calculate volume score
            val volumeScore = if (maxTotalReviewCount > 0) {
              summary.totalReviewCount.toDouble / maxTotalReviewCount
            } else 0.0
            
            // 7. Calculate consistency score (bonus for domains with multiple reviews)
            val consistencyBonus = if (summary.totalReviewCount > 10) 0.05 else 0.0
            
            // Calculate final rank score with weights
            val rankScore = (
              trafficScore * TRAFFIC_WEIGHT +
              reviewCountScore * REVIEW_COUNT_WEIGHT +
              sentimentScore * SENTIMENT_WEIGHT +
              recencyScore * RECENCY_WEIGHT +
              categoryScore * CATEGORY_WEIGHT +
              volumeScore * 0.05 + // Small bonus for overall volume
              consistencyBonus
            ) * 100.0 // Scale to 0-100 for readability
            
            summary.copy(rankScore = rankScore)
          }
          .sortWith { (s1, s2) =>
            if (s1.rankScore != s2.rankScore) {
              s1.rankScore > s2.rankScore
            } else {
              // Multi-factor tiebreaking
              val s1Traffic = s1.traffic.getOrElse(0L)
              val s2Traffic = s2.traffic.getOrElse(0L)
              
              if (s1Traffic != s2Traffic) {
                s1Traffic > s2Traffic
              } else if (s1.recentReviewCount != s2.recentReviewCount) {
                s1.recentReviewCount > s2.recentReviewCount
              } else {
                s1.sentimentSumRecentReviews > s2.sentimentSumRecentReviews
              }
            }
          }
          .take(10)

        replyTo ! rankedSummaries
        Behaviors.same
    }
  }
}