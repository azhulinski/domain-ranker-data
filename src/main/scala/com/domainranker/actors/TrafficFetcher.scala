package com.domainranker.actors

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import com.domainranker.models.DomainTraffic

import java.time.Instant
import scala.util.Random

object TrafficFetcher {
  // Command protocol
  sealed trait Command
  case class FetchTraffic(domains: Set[String], replyTo: ActorRef[List[DomainTraffic]]) extends Command

  def apply(): Behavior[Command] = {
    Behaviors.setup { context =>
      // Random generator for traffic values
      val random = new Random()
      
      // Cache to maintain consistent traffic values per domain
      var trafficCache = Map.empty[String, (Long, Instant)]

      Behaviors.receiveMessage {
        case FetchTraffic(domains, replyTo) =>
          context.log.info(s"Generating traffic data for ${domains.size} domains")
          
          val currentTime = Instant.now()
          
          // Generate traffic data for each domain
          val trafficData = domains.toList.map { domain =>
            // Get cached value or generate a new one
            val (traffic, lastUpdated) = trafficCache.get(domain) match {
              case Some((cachedTraffic, cachedTimestamp)) => 
                // If data is older than 24 hours, add variation
                val hoursSinceUpdate = java.time.Duration.between(cachedTimestamp, currentTime).toHours
                
                if (hoursSinceUpdate > 24) {
                  // Add variation (±10%) to existing traffic value for "fresh" data
                  val variation = 0.9 + (random.nextDouble() * 0.2)
                  ((cachedTraffic * variation).toLong, currentTime)
                } else {
                  // Keep the same traffic but note we checked it now
                  (cachedTraffic, currentTime)
                }
                
              case None =>
                // Generate new traffic value based on domain characteristics
                (generateTrafficForDomain(domain, random), currentTime)
            }
            
            // Update the cache with the new value
            trafficCache = trafficCache + (domain -> (traffic, lastUpdated))
            
            // Create domain traffic object - explicitly typed
            val domainTraffic: DomainTraffic = DomainTraffic(domain, traffic, lastUpdated)
            domainTraffic
          }
          
          // Send traffic data back to requester
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
    
    // Categorize domains into traffic tiers
    val trafficRange = if (isHighTrafficDomain(baseDomain)) {
      // High traffic sites (100K-5M)
      (100000L, 5000000L)
    } else if (isMediumTrafficDomain(baseDomain)) {
      // Medium traffic sites (10K-100K)
      (10000L, 100000L)
    } else {
      // Low traffic sites (100-10K)
      (100L, 10000L)
    }
    
    // Generate random traffic within the appropriate range
    val (min, max) = trafficRange
    min + (random.nextDouble() * (max - min)).toLong
  }
  
  /**
   * Determines if a domain is likely to have high traffic
   */
  private def isHighTrafficDomain(domain: String): Boolean = {
    // Popular websites and short domains tend to have higher traffic
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
    // Medium traffic domains often have certain TLDs or characteristics
    domain.endsWith(".com") || 
    domain.endsWith(".org") || 
    domain.endsWith(".net") || 
    domain.endsWith(".co") ||
    domain.length < 15
  }
}