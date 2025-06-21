package com.domainranker.actors

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import com.domainranker.models.DomainTraffic
import play.api.libs.json._

import java.time.Instant
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success, Try}

object TrafficFetcher {
  sealed trait Command

  case class FetchTraffic(domains: Set[String], replyTo: ActorRef[List[DomainTraffic]]) extends Command

  private case class TrafficResult(traffic: List[DomainTraffic], replyTo: ActorRef[List[DomainTraffic]]) extends Command

  private case class TrafficFailure(error: Throwable, replyTo: ActorRef[List[DomainTraffic]]) extends Command

  def apply(): Behavior[Command] = Behaviors.setup { context =>
    implicit val system = context.system
    implicit val ec: ExecutionContextExecutor = system.executionContext

    val vstatApiBaseUrl = "https://api.vstat.info/traffic?domain="

    Behaviors.receiveMessage {
      case FetchTraffic(domains, replyTo) =>
        context.log.info(s"Fetching traffic for ${domains.size} domains.")

        val trafficFutures = domains.map { domain =>
          val url = s"$vstatApiBaseUrl$domain"
          Http().singleRequest(HttpRequest(uri = url))
            .flatMap(_.entity.toStrict(10.seconds))
            .map(_.data.utf8String)
            .map { jsonString =>
              Try {
                val json = Json.parse(jsonString)
                val trafficValue = (json \ "traffic").as[Long]
                Some(DomainTraffic(domain, trafficValue, Instant.now()))
              }.getOrElse {
                None
              }
            }
            .recover {
              case e: Exception => None
            }
        }

        Future.sequence(trafficFutures).onComplete {
          case Success(trafficData) =>
            val validTraffic = trafficData.flatten.toList
            context.self ! TrafficResult(validTraffic, replyTo)
          case Failure(ex) =>
            context.self ! TrafficFailure(ex, replyTo)
        }

        Behaviors.same

      case TrafficResult(traffic, replyTo) =>
        context.log.info(s"Fetched traffic for ${traffic.size} domains.")
        replyTo ! traffic
        Behaviors.same

      case TrafficFailure(error, replyTo) =>
        context.log.error(s"Failed to fetch traffic for domains: ${error.getMessage}")
        replyTo ! List.empty[DomainTraffic]
        Behaviors.same
    }
  }
}