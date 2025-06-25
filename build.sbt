val AkkaVersion = "2.8.5"
val AkkaHttpVersion = "10.5.3"
val Slf4jVersion = "1.7.36"
val LogbackVersion = "1.2.11"

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
      "org.slf4j" % "slf4j-api" % Slf4jVersion,
      "ch.qos.logback" % "logback-classic" % LogbackVersion,
      "ch.qos.logback" % "logback-core" % LogbackVersion
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
    ),

    assembly / assemblyJarName := "DomainRankerData-assembly.jar",
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "services", xs@_*) => MergeStrategy.concat
      case PathList("META-INF", xs@_*) => MergeStrategy.discard
      case "reference.conf" => MergeStrategy.concat
      case x if x.endsWith(".properties") => MergeStrategy.concat
      case x => MergeStrategy.first
    },
    ThisBuild / conflictManager := ConflictManager.strict,
    ThisBuild / dependencyOverrides ++= Seq(
      "org.slf4j" % "slf4j-api" % Slf4jVersion,
      "ch.qos.logback" % "logback-classic" % LogbackVersion,
      "ch.qos.logback" % "logback-core" % LogbackVersion
    )
  )