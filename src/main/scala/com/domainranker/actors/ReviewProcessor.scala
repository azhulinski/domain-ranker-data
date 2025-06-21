package com.domainranker.actors

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.RawHeader
import com.domainranker.models.TrustpilotReview
import play.api.libs.json._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success, Try}

object ReviewProcessor {
  sealed trait Command

  case class ProcessReviews(reviews: List[TrustpilotReview], replyTo: ActorRef[List[TrustpilotReview]]) extends Command

  private case class ProcessingResult(reviews: List[TrustpilotReview], replyTo: ActorRef[List[TrustpilotReview]]) extends Command

  private case class ProcessingFailure(error: Throwable, originalReviews: List[TrustpilotReview], replyTo: ActorRef[List[TrustpilotReview]]) extends Command

  def apply(openAiApiKey: String): Behavior[Command] = Behaviors.setup { context =>
    implicit val system = context.system
    implicit val ec: ExecutionContextExecutor = system.executionContext

    val openAiApiUrl = "https://api.openai.com/v1/chat/completions"
    val sentimentPromptTemplate =
      """
        |You are a sentiment analysis AI. Analyze the following review text and return a sentiment score between -1.0 (very negative) and 1.0 (very positive).
        |Only return the numerical score. Do not include any other text or explanation.
        |
        |Review Text: "{review_text}"
        |Sentiment Score:
      """.stripMargin

    Behaviors.receiveMessage {
      case ProcessReviews(reviews, replyTo) =>
        context.log.info(s"Processing ${reviews.size} reviews for sentiment analysis.")

        val processedFutures = reviews.map { review =>
          val prompt = sentimentPromptTemplate.replace("{review_text}", review.reviewText)
          val requestBody = Json.obj(
            "model" -> "gpt-4o-mini",
            "messages" -> Json.arr(
              Json.obj("role" -> "system", "content" -> "You are a sentiment analysis AI."),
              Json.obj("role" -> "user", "content" -> prompt)
            ),
            "max_tokens" -> 10
          )

          Http().singleRequest(
              HttpRequest(
                method = HttpMethods.POST,
                uri = openAiApiUrl,
                headers = List(
                  RawHeader("Content-Type", "application/json"),
                  RawHeader("Authorization", s"Bearer $openAiApiKey")
                ),
                entity = HttpEntity(ContentTypes.`application/json`, requestBody.toString())
              )
            )
            .flatMap(_.entity.toStrict(10.seconds))
            .map(_.data.utf8String)
            .map { jsonString =>
              val json = Json.parse(jsonString)
              val content = (json \ "choices" \ 0 \ "message" \ "content").asOpt[String]

              content.flatMap(s => Try(s.trim.toDouble).toOption) match {
                case Some(score) => review.copy(sentimentScore = Some(score))
                case None => review
              }
            }
            .recover {
              case e: Exception => review
            }
        }

        Future.sequence(processedFutures).onComplete {
          case Success(processedReviews) =>
            context.self ! ProcessingResult(processedReviews, replyTo)
          case Failure(ex) =>
            context.self ! ProcessingFailure(ex, reviews, replyTo)
        }

        Behaviors.same

      case ProcessingResult(reviews, replyTo) =>
        context.log.info(s"Finished sentiment analysis for ${reviews.size} reviews.")
        replyTo ! reviews
        Behaviors.same

      case ProcessingFailure(error, originalReviews, replyTo) =>
        context.log.error(s"Failed to process reviews for sentiment: ${error.getMessage}")
        replyTo ! originalReviews
        Behaviors.same
    }
  }
}