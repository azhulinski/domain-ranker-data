package com.domainranker.actors

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{Authorization, OAuth2BearerToken}
import akka.http.scaladsl.settings.ConnectionPoolSettings
import com.domainranker.models.TrustpilotReview
import org.slf4j.{Logger, LoggerFactory}
import play.api.libs.json.Json

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success}

object ReviewProcessor {
  private val logger: Logger = LoggerFactory.getLogger(ReviewProcessor.getClass)

  sealed trait Command

  case class ProcessReviews(reviews: List[TrustpilotReview], replyTo: ActorRef[List[TrustpilotReview]]) extends Command

  private case class SentimentResult(originalReview: TrustpilotReview, processedReview: TrustpilotReview) extends Command

  def apply(openAiApiKey: String): Behavior[Command] = Behaviors.setup { context =>
    implicit val system: ActorSystem[Nothing] = context.system
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
        logger.debug(s"Processing sentiment for ${reviews.size} reviews")

        reviews.foreach { review =>
          processReviewSentiment(review, openAiApiUrl, openAiApiKey, sentimentPromptTemplate)
            .onComplete {
              case Success(processedReview) =>
                context.self ! SentimentResult(review, processedReview)
              case Failure(ex) =>
                logger.error(s"Failed to process review ${review.id}: ${ex.getMessage}")
                context.self ! SentimentResult(review, review)
            }
        }
        collectingResults(reviews.size, Map.empty, reviews, replyTo)
    }
  }

  private def collectingResults(
                                 remaining: Int,
                                 results: Map[String, TrustpilotReview],
                                 originalReviews: List[TrustpilotReview],
                                 replyTo: ActorRef[List[TrustpilotReview]]
                               ): Behavior[Command] = {
    Behaviors.receiveMessage {
      case SentimentResult(original, processed) =>
        logger.debug(s"Received sentiment result for review ${original.id}")

        val newResults = results + (original.id -> processed)
        val newRemaining = remaining - 1

        if (newRemaining <= 0) {
          logger.info(s"All ${originalReviews.size} reviews processed, returning results")
          val processedReviewsMap = newResults

          val finalReviews = originalReviews.map { review =>
            processedReviewsMap.getOrElse(review.id, review)
          }

          replyTo ! finalReviews

          Behaviors.same
        } else {
          collectingResults(newRemaining, newResults, originalReviews, replyTo)
        }
      case _ =>
        logger.warn("Received unexpected message in collectingResults")
        Behaviors.same
    }
  }

  private def processReviewSentiment(
                                      review: TrustpilotReview,
                                      apiUrl: String,
                                      apiKey: String,
                                      promptTemplate: String
                                    )(implicit system: ActorSystem[_], ec: ExecutionContextExecutor): Future[TrustpilotReview] = {
    logger.debug(s"Processing sentiment for review ${review.id} of domain ${review.domain}")
    
    val prompt = promptTemplate.replace("{review_text}", review.reviewText)
    val requestBody = Json.obj(
      "model" -> "gpt-4o-mini",
      "messages" -> Json.arr(
        Json.obj("role" -> "system", "content" -> "You are a sentiment analysis AI."),
        Json.obj("role" -> "user", "content" -> prompt)
      ),
      "max_tokens" -> 10
    )

    val request = HttpRequest(
      method = HttpMethods.POST,
      uri = apiUrl,
      entity = HttpEntity(ContentTypes.`application/json`, requestBody.toString())
    )

    val requestWithAuth = request.addHeader(Authorization(OAuth2BearerToken(apiKey)))

    val connectionPoolSettings = ConnectionPoolSettings(system)
      .withMaxOpenRequests(128)
      .withMaxConnections(8)
      .withPipeliningLimit(1)
      .withMaxRetries(3)

    Http().singleRequest(requestWithAuth, settings = connectionPoolSettings)
      .flatMap { response =>
        if (response.status.isSuccess()) {
          logger.debug(s"Received successful response for review ${review.id}")
          response.entity.toStrict(10.seconds)
            .map(_.data.utf8String)
            .map { jsonString =>
              try {
                val json = Json.parse(jsonString)
                val content = (json \ "choices" \ 0 \ "message" \ "content").asOpt[String]

              content.flatMap(s => Some(s.trim.toDouble)) match {
                case Some(score) => review.copy(sentimentScore = Some(score))
                case None => review
                }
              } catch {
                case ex: Exception =>
                  logger.error(s"Error processing sentiment JSON for review ${review.id}: ${ex.getMessage}")
                  review
              }
            }
            .recover {
              case ex: Exception =>
                logger.error(s"Entity extraction error for review ${review.id}: ${ex.getMessage}")
                review
            }
        } else {
          logger.warn(s"API returned error status ${response.status} for review ${review.id}")
          Future.successful(review)
        }
      }
      .recoverWith {
        case ex: Exception if ex.getMessage.contains("max-open-requests") =>
          val scheduler = system.classicSystem.scheduler
          akka.pattern.after(1.second, scheduler)(
            processReviewSentiment(review, apiUrl, apiKey, promptTemplate)
          )
        case ex: Exception =>
          logger.error(s"HTTP request error for review ${review.id}: ${ex.getMessage}", ex)
          Future.successful(review)
      }
  }
}