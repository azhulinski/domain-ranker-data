package com.domainranker

import akka.actor.typed.{ActorSystem, Behavior}
import com.domainranker.actors.Scheduler
import com.typesafe.config.ConfigFactory


object Main extends App {

  val config = ConfigFactory.load()

  val openAiApiKey = config.getString("domainranker.apis.openai.api-key")

  if (openAiApiKey == "YOUR_OPENAI_API_KEY_HERE") {
    println("WARNING: OPENAI_API_KEY environment variable not set. Using placeholder.")
    println("Please set OPENAI_API_KEY to your actual OpenAI API key.")
  }

  val rootBehavior: Behavior[Scheduler.Command] = Scheduler(openAiApiKey)
  val system = ActorSystem(rootBehavior, "DomainRankerService")

}