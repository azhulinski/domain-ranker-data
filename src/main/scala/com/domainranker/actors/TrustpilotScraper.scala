package com.domainranker.actors

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpRequest
import akka.stream.scaladsl.Source
import com.domainranker.models.TrustpilotReview
import org.jsoup.Jsoup

import java.time.Instant
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}

object TrustpilotScraper {
  sealed trait Command

  case class ScrapeAllCategoryLinks(replyTo: ActorRef[List[TrustpilotReview]]) extends Command

  private case class ScrapingResult(reviews: List[TrustpilotReview], replyTo: ActorRef[List[TrustpilotReview]]) extends Command

  private case class ScrapingFailure(error: Throwable, replyTo: ActorRef[List[TrustpilotReview]]) extends Command

  def apply(): Behavior[Command] = Behaviors.setup { context =>
    implicit val system = context.system
    implicit val ec: ExecutionContextExecutor = system.executionContext

    val trustpilotBaseUrl = "https://www.trustpilot.com"
    val categoriesUrl = s"$trustpilotBaseUrl/categories"


    Behaviors.receiveMessage {
      case ScrapeAllCategoryLinks(replyTo) =>
        context.log.info("Starting to scrape all Trustpilot category links")

        // First fetch the categories page to extract all category links
        val categoriesFuture = Http().singleRequest(HttpRequest(uri = categoriesUrl))
          .flatMap(_.entity.toStrict(10.seconds))
          .map(_.data.utf8String)
          .map { html =>
            Try {
              val doc = Jsoup.parse(html)
              // Extract all category links with href="/categories/something"
              val categoryLinks = doc.select("a[href^=/categories/]").asScala
                .map(_.attr("href"))
                .filter(_.startsWith("/categories/"))
                .map(_.substring("/categories/".length))
                .filter(_.nonEmpty)
                .toList

              //              context.log.info(s"Found ${categoryLinks.size} category links")
              categoryLinks
            }.getOrElse(List.empty[String])
          }
          .recover {
            case e: Exception =>
              //              context.log.error(s"Failed to fetch categories: ${e.getMessage}")
              List.empty[String]
          }

        // Then for each category, fetch the latest reviews
        categoriesFuture.flatMap { categories =>
          val allReviews = Source(categories)
            .mapAsync(1) { category =>
              // Sort by latest reviews
              val latestReviewsUrl = s"$trustpilotBaseUrl/categories/$category?sort=latest_review"

              Http().singleRequest(HttpRequest(uri = latestReviewsUrl))
                .flatMap(_.entity.toStrict(10.seconds))
                .map(_.data.utf8String)
                .map { html =>
                  Try {
                    val doc = Jsoup.parse(html)
                    // Get all business cards/listings
                    val businessCards = doc.select(".styles_card__8oW3J").asScala.toList

                    // For each business, extract domain and get latest reviews
                    val reviewFutures = businessCards.map { card =>
                      val businessUrl = card.select("a[href^=/review]").attr("href")
                      val domain = extractDomain(businessUrl)

                      if (domain.nonEmpty) {
                        // Get the latest reviews for this domain
                        val reviewsUrl = s"$trustpilotBaseUrl/review/$domain"

                        Http().singleRequest(HttpRequest(uri = reviewsUrl))
                          .flatMap(_.entity.toStrict(10.seconds))
                          .map(_.data.utf8String)
                          .map { reviewsHtml =>
                            Try {
                              val reviewsDoc = Jsoup.parse(reviewsHtml)
                              val reviewElements = reviewsDoc.select("[data-reviews-list-start]")
                                .select("[data-service-review-card-paper]").asScala.take(10).toList

                              reviewElements.flatMap { reviewElement =>
                                val reviewText = reviewElement.select("[data-service-review-text-typography]").text().trim
                                val reviewDateStr = reviewElement.select("time[datetime]").attr("datetime")

                                if (reviewText.nonEmpty && reviewDateStr.nonEmpty) {
                                  Try {
                                    val reviewId = reviewElement.select("a[href^=/reviews/]").attr("href").replaceAll("^/reviews/", "")
                                    val reviewDate = Instant.parse(reviewDateStr)
                                    Some(TrustpilotReview(reviewId, domain, category, reviewText, reviewDate))
                                  }.toOption.flatten
                                } else {
                                  None
                                }
                              }
                            }.getOrElse(List.empty[TrustpilotReview])
                          }
                          .recover {
                            case e: Exception =>
                              //                              context.log.error(s"Failed to fetch reviews for $domain: ${e.getMessage}")
                              List.empty[TrustpilotReview]
                          }
                      } else {
                        Future.successful(List.empty[TrustpilotReview])
                      }
                    }

                    Future.sequence(reviewFutures).map(_.flatten.toList)
                  }.getOrElse(Future.successful(List.empty[TrustpilotReview]))
                }
                .recover {
                  case e: Exception =>
                    //                    context.log.error(s"Failed to process category $category: ${e.getMessage}")
                    Future.successful(List.empty[TrustpilotReview])
                }
                .flatten
            }
            .runFold(List.empty[TrustpilotReview])(_ ++ _)

          allReviews
        }.onComplete {
          case Success(reviews) =>
            context.self ! ScrapingResult(reviews, replyTo)
          case Failure(ex) =>
            context.self ! ScrapingFailure(ex, replyTo)
        }

        Behaviors.same

      case ScrapingResult(reviews, replyTo) =>
        //        context.log.info(s"Scraped ${reviews.size} reviews in total.")
        replyTo ! reviews
        Behaviors.same

      case ScrapingFailure(error, replyTo) =>
        //        context.log.error(s"Failed to scrape all categories: ${error.getMessage}")
        replyTo ! List.empty[TrustpilotReview]
        Behaviors.same
    }
  }

  private def extractDomain(url: String): String = {
    val domainPattern = ".*/review/([^/?]+).*".r
    url match {
      case domainPattern(domain) => domain
      case _ => ""
    }
  }
}