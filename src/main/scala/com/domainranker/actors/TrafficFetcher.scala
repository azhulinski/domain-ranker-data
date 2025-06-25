package com.domainranker.actors

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import com.domainranker.models.DomainTraffic
import org.slf4j.{Logger, LoggerFactory}

import java.time.Instant
import scala.util.Random

object TrafficFetcher {
  private val logger: Logger = LoggerFactory.getLogger(TrafficFetcher.getClass)

  sealed trait Command

  case class FetchTraffic(domains: Set[String], replyTo: ActorRef[List[DomainTraffic]]) extends Command

  def apply(): Behavior[Command] = {
    Behaviors.setup { _ =>

      val random = new Random()
      var trafficCache = Map.empty[String, (Long, Instant)]

      Behaviors.receiveMessage {
        case FetchTraffic(domains, replyTo) =>
          logger.info(s"Generating traffic data for ${domains.size} domains")

          val currentTime = Instant.now()

          val trafficData = domains.toList.map { domain =>
            val (traffic, lastUpdated) = trafficCache.get(domain) match {
              case Some((cachedTraffic, cachedTimestamp)) =>
                val hoursSinceUpdate = java.time.Duration.between(cachedTimestamp, currentTime).toHours

                if (hoursSinceUpdate > 24) {
                  val variation = 0.9 + (random.nextDouble() * 0.2)
                  ((cachedTraffic * variation).toLong, currentTime)
                } else {
                  (cachedTraffic, currentTime)
                }

              case None =>
                (generateTrafficForDomain(domain, random), currentTime)
            }
            trafficCache = trafficCache + (domain -> (traffic, lastUpdated))
            val domainTraffic: DomainTraffic = DomainTraffic(domain, traffic, lastUpdated)
            domainTraffic
          }
          replyTo ! trafficData
          Behaviors.same
      }
    }
  }

  /**
   * Generates a plausible traffic value for a domain based on its characteristics
   */
  private def generateTrafficForDomain(domain: String, random: Random): Long = {
    val baseDomain = domain.replaceAll("^(www\\.|m\\.|app\\.)", "")

    val trafficRange = if (isHighTrafficDomain(baseDomain)) {
      (100000L, 5000000L)
    } else if (isMediumTrafficDomain(baseDomain)) {
      (10000L, 100000L)
    } else {
      (100L, 10000L)
    }
    val (min, max) = trafficRange
    min + (random.nextDouble() * (max - min)).toLong
  }

  /**
   * Determines if a domain is likely to have high traffic
   */
  private def isHighTrafficDomain(domain: String): Boolean = {
    val highTrafficKeywords = List(
      "google", "amazon", "facebook", "microsoft", "apple",
      "netflix", "twitter", "instagram", "linkedin", "youtube"
    )
    domain.length < 8 ||
      highTrafficKeywords.exists(domain.contains) ||
      (domain.endsWith(".com") && domain.length < 10)
  }

  /**
   * Determines if a domain is likely to have medium traffic
   */
  private def isMediumTrafficDomain(domain: String): Boolean = {
    domain.endsWith(".com") ||
      domain.endsWith(".org") ||
      domain.endsWith(".net") ||
      domain.endsWith(".co") ||
      domain.length < 15
  }
}