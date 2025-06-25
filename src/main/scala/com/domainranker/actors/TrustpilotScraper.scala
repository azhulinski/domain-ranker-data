package com.domainranker.actors

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpRequest
import akka.stream.scaladsl.Source
import com.domainranker.models.TrustpilotReview
import com.typesafe.config.ConfigFactory
import org.jsoup.Jsoup
import org.slf4j.{Logger, LoggerFactory}

import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}

object TrustpilotScraper {
  private val logger: Logger = LoggerFactory.getLogger(TrustpilotScraper.getClass)

  sealed trait Command

  case class ScrapeAllCategoryLinks(replyTo: ActorRef[List[TrustpilotReview]]) extends Command

  private case class ScrapingResult(reviews: List[TrustpilotReview], replyTo: ActorRef[List[TrustpilotReview]]) extends Command

  private case class ScrapingFailure(error: Throwable, replyTo: ActorRef[List[TrustpilotReview]]) extends Command

  private val appStartTime = Instant.now()

  def apply(): Behavior[Command] = Behaviors.setup { context =>
    implicit val system = context.system
    implicit val ec: ExecutionContextExecutor = system.executionContext

    val trustpilotBaseUrl = "https://www.trustpilot.com"
    val categoriesUrl = s"$trustpilotBaseUrl/categories"
    val config = ConfigFactory.load()

    Behaviors.receiveMessage {
      case ScrapeAllCategoryLinks(replyTo) =>
        logger.info("Starting to scrape Trustpilot categories")

        val allowedCategories = config.getStringList("domainranker.categories.allowed").asScala.toList
        val categoriesFuture = if (allowedCategories.isEmpty) {
          logger.info("No specific categories configured, fetching all available categories")
          Http().singleRequest(HttpRequest(uri = categoriesUrl))
            .flatMap(_.entity.toStrict(10.seconds))
            .map(_.data.utf8String)
            .map { html =>
              Try {
                val doc = Jsoup.parse(html)
                val categoryLinks = doc.select("a[href^=/categories/]").asScala
                  .map(_.attr("href"))
                  .filter(_.startsWith("/categories/"))
                  .map(_.substring("/categories/".length))
                  .filter(_.nonEmpty)
                  .toList

                logger.debug(s"Found ${categoryLinks.size} categories on Trustpilot")
                categoryLinks
              }.getOrElse {
                logger.warn("Failed to parse categories HTML")
                List.empty[String]
              }
            }
            .recover {
              case ex: Exception =>
                logger.error(s"Error fetching categories: ${ex.getMessage}")
                List.empty[String]
            }
        } else {
          logger.info(s"Using ${allowedCategories.size} configured categories")
          Future.successful(allowedCategories)
        }

        categoriesFuture.flatMap { categories =>
          logger.info(s"Processing ${categories.size} categories")

          val allReviews = Source(categories)
            .mapAsync(1) { category =>
              logger.debug(s"Fetching business listings for category: $category")
              val latestReviewsUrl = s"$trustpilotBaseUrl/categories/$category?sort=latest_review"

              Http().singleRequest(HttpRequest(uri = latestReviewsUrl))
                .flatMap(_.entity.toStrict(10.seconds))
                .map(_.data.utf8String)
                .map { html =>
                  Try {
                    val doc = Jsoup.parse(html)

                    val businessCards = doc.select(".styles_card__8oW3J").asScala.toList.take(10)
                    logger.debug(s"Found ${businessCards.size} businesses in category $category")

                    val reviewFutures = businessCards.map { card =>
                      val businessUrl = card.select("a[href^=/review]").attr("href")
                      val domain = extractDomain(businessUrl)

                      if (domain.nonEmpty) {
                        logger.debug(s"Fetching reviews for domain: $domain")
                        val reviewsUrl = s"$trustpilotBaseUrl/review/$domain"

                        Http().singleRequest(HttpRequest(uri = reviewsUrl))
                          .flatMap(_.entity.toStrict(10.seconds))
                          .map(_.data.utf8String)
                          .map { reviewsHtml =>
                            Try {
                              val reviewsDoc = Jsoup.parse(reviewsHtml)
                              val reviewElements = reviewsDoc.select("[data-reviews-list-start]")
                                .select("[data-service-review-card-paper]").asScala.take(10).toList

                              logger.debug(s"Found ${reviewElements.size} reviews for domain $domain")

                              reviewElements.flatMap { reviewElement =>
                                val reviewText = reviewElement.select("[data-service-review-text-typography]").text().trim
                                val reviewDateStr = reviewElement.select("time[datetime]").attr("datetime")

                                if (reviewText.nonEmpty && reviewDateStr.nonEmpty) {
                                  Try {
                                    val reviewId = reviewElement.select("a[href^=/reviews/]").attr("href").replaceAll("^/reviews/", "")
                                    val reviewDate = Instant.parse(reviewDateStr)

                                    val cutoffTime = appStartTime.minus(48, ChronoUnit.HOURS)
                                    if (reviewDate.isAfter(cutoffTime)) {
                                      logger.debug(s"Adding recent review $reviewId for domain $domain")
                                      Some(TrustpilotReview(reviewId, domain, category, reviewText, reviewDate))
                                    } else {
                                      logger.debug(s"Skipping old review $reviewId for domain $domain")
                                      None
                                    }
                                  }.toOption.flatten
                                } else {
                                  logger.debug(s"Skipping review with empty text or date for domain $domain")
                                  None
                                }
                              }
                            }.getOrElse {
                              logger.warn(s"Failed to parse reviews HTML for domain $domain")
                              List.empty[TrustpilotReview]
                            }
                          }
                          .recover {
                            case ex: Exception =>
                              logger.error(s"Error fetching reviews for domain $domain: ${ex.getMessage}")
                              List.empty[TrustpilotReview]
                          }
                      } else {
                        logger.warn(s"Failed to extract domain from URL: $businessUrl")
                        Future.successful(List.empty[TrustpilotReview])
                      }
                    }
                    Future.sequence(reviewFutures).map(_.flatten.toList)
                  }.getOrElse {
                    logger.warn(s"Failed to parse HTML for category $category")
                    Future.successful(List.empty[TrustpilotReview])
                  }
                }
                .recover {
                  case ex: Exception =>
                    logger.error(s"Error fetching businesses for category $category: ${ex.getMessage}")
                    Future.successful(List.empty[TrustpilotReview])
                }
                .flatten
            }
            .runFold(List.empty[TrustpilotReview])(_ ++ _)

          allReviews
        }.onComplete {
          case Success(reviews) =>
            logger.info(s"Successfully scraped ${reviews.size} reviews from Trustpilot")
            context.self ! ScrapingResult(reviews, replyTo)
          case Failure(ex) =>
            logger.error(s"Failed to scrape reviews: ${ex.getMessage}", ex)
            context.self ! ScrapingFailure(ex, replyTo)
        }

        Behaviors.same

      case ScrapingResult(reviews, replyTo) =>
        logger.debug(s"Sending ${reviews.size} scraped reviews to requestor")
        replyTo ! reviews
        Behaviors.same

      case ScrapingFailure(error, replyTo) =>
        logger.error(s"Scraping failed with error: ${error.getMessage}", error)
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