package com.domainranker.actors

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{Authorization, OAuth2BearerToken}
import com.domainranker.models.TrustpilotReview
import play.api.libs.json.Json

import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Random, Success, Try}

object ReviewProcessor {
  sealed trait Command

  case class ProcessReviews(reviews: List[TrustpilotReview], replyTo: ActorRef[List[TrustpilotReview]]) extends Command

  private case class ProcessingResult(reviews: List[TrustpilotReview], replyTo: ActorRef[List[TrustpilotReview]]) extends Command

  private case class ProcessingFailure(error: Throwable, originalReviews: List[TrustpilotReview], replyTo: ActorRef[List[TrustpilotReview]]) extends Command

  // Adding this to capture sentiment analysis result for a single review
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
        // Take a subset of reviews to process
        val reviewsToProcess = Random.shuffle(reviews).take(5)
        context.log.debug(s"Processing sentiment for ${reviews.size} reviews")
        
        // Create a collection to track processed reviews
        var processedReviews = Map.empty[String, TrustpilotReview]
        
        // Process each review individually
        reviews.foreach { review =>
          // Send each review to be processed separately
          processReviewSentiment(review, openAiApiUrl, openAiApiKey, sentimentPromptTemplate)
            .onComplete {
              case Success(processedReview) =>
                // Send the result back to self to handle in actor context
                context.self ! SentimentResult(review, processedReview)
              case Failure(_) =>
                // Log error and keep original review
                context.self ! SentimentResult(review, review)
            }
        }
        
        // Move to collecting state
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
        // Add this result to our collection
        val newResults = results + (original.id -> processed)
        val newRemaining = remaining - 1
        
        if (newRemaining <= 0) {
          // We've collected all results, merge with original reviews
          val processedReviewsMap = newResults
          
          // Replace processed reviews in the original list
          val finalReviews = originalReviews.map { review =>
            processedReviewsMap.getOrElse(review.id, review)
          }
          
          // Send back to requester
          replyTo ! finalReviews
          
          // Return to idle state
          Behaviors.same
        } else {
          // Keep collecting results
          collectingResults(newRemaining, newResults, originalReviews, replyTo)
        }
        
      case ProcessReviews(reviews, newReplyTo) =>
        // If we get a new request while processing, complete the current one first
        Behaviors.same
        
      case _ => 
        // Ignore other messages while collecting
        Behaviors.same
    }
  }
  
  // Move the sentiment processing to a separate method that doesn't use context
  private def processReviewSentiment(
                                    review: TrustpilotReview,
                                    apiUrl: String,
                                    apiKey: String,
                                    promptTemplate: String
                                   )(implicit system: ActorSystem[_], ec: ExecutionContextExecutor): Future[TrustpilotReview] = {
    val prompt = promptTemplate.replace("{review_text}", review.reviewText)
    val requestBody = Json.obj(
      "model" -> "gpt-4o-mini",
      "messages" -> Json.arr(
        Json.obj("role" -> "system", "content" -> "You are a sentiment analysis AI."),
        Json.obj("role" -> "user", "content" -> prompt)
      ),
      "max_tokens" -> 10
    )

    // Create an HttpRequest with proper content type in the entity
    val request = HttpRequest(
      method = HttpMethods.POST,
      uri = apiUrl,
      entity = HttpEntity(ContentTypes.`application/json`, requestBody.toString())
    )

    // Add only the Authorization header - let Akka handle Content-Type
    val requestWithAuth = request.addHeader(Authorization(OAuth2BearerToken(apiKey)))

    Http().singleRequest(requestWithAuth)
      .flatMap(_.entity.toStrict(10.seconds))
      .map(_.data.utf8String)
      .map { jsonString =>
        try {
          val json = Json.parse(jsonString)
          val content = (json \ "choices" \ 0 \ "message" \ "content").asOpt[String]

          content.flatMap(s => Try(s.trim.toDouble).toOption) match {
            case Some(score) => review.copy(sentimentScore = Some(score))
            case None => review // Return original review if can't extract score
          }
        } catch {
          case _: Exception => review // Return original review on error
        }
      }
      .recover {
        case _: Exception => review // Return original review on error
      }
  }
}