val AkkaVersion = "2.8.5"
val AkkaHttpVersion = "10.5.3"

lazy val domainRanker = project
  .in(file("."))
  .settings(
    name := "DomainRankerData",
    version := "0.1.0-SNAPSHOT",
    scalaVersion := "2.13.14",
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-actor-typed" % AkkaVersion,
      "com.typesafe.akka" %% "akka-stream" % AkkaVersion,
      "com.typesafe.akka" %% "akka-http" % AkkaHttpVersion,
      "com.typesafe.akka" %% "akka-http-spray-json" % AkkaHttpVersion,
      "org.jsoup" % "jsoup" % "1.17.2",
      "com.typesafe.play" %% "play-json" % "2.9.4",
      "com.typesafe.akka" %% "akka-actor-testkit-typed" % AkkaVersion % Test,
      "org.scalatest" %% "scalatest" % "3.2.19" % Test,
      "ch.qos.logback" % "logback-classic" % "1.5.18"
    ),
    scalacOptions ++= Seq(
      "-encoding", "UTF-8",
      "-unchecked",
      "-deprecation",
      "-feature",
      "-Ywarn-unused",
      "-Ywarn-dead-code",
      "-Ywarn-numeric-widen",
      "-Xlint"
    )
  )