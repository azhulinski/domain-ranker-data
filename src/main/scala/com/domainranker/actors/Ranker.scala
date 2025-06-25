package com.domainranker.actors

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import com.domainranker.models.DomainSummary

object Ranker {
  sealed trait Command

  case class CalculateRanks(summaries: List[DomainSummary], replyTo: ActorRef[List[DomainSummary]]) extends Command

  def apply(): Behavior[Command] = Behaviors.setup { context =>
    Behaviors.receiveMessage {
      case CalculateRanks(summaries, replyTo) =>
        context.log.info(s"Calculating ranks for ${summaries.size} domains.")

        val rankedSummaries = summaries
          .map { summary =>

            val reviewCountScore = summary.recentReviewCount.toDouble
            val sentimentScore = summary.sentimentSumRecentReviews

            val rankScore = reviewCountScore * sentimentScore

            summary.copy(rankScore = rankScore)
          }
          .sortWith { (s1, s2) =>
            if (s1.recentReviewCount != s2.recentReviewCount) {

              s1.recentReviewCount > s2.recentReviewCount
            } else {

              s1.sentimentSumRecentReviews > s2.sentimentSumRecentReviews
            }
          }
        replyTo ! rankedSummaries
        Behaviors.same
    }
  }
}