import sbt._

object Dependencies {
  val akkaHttpVersion     = "10.2.4"
  val akkaVersion         = "2.6.14"
  val akkaHttpJsonVersion = "1.37.0"
  val circeVersion        = "0.14.1"

  lazy val akkaDeps = Seq(
    "com.typesafe.akka" %% "akka-http"                % akkaHttpVersion,
    "de.heikoseeberger" %% "akka-http-circe"          % akkaHttpJsonVersion,
    "com.typesafe.akka" %% "akka-actor-typed"         % akkaVersion,
    "com.typesafe.akka" %% "akka-stream"              % akkaVersion,
    "com.typesafe.akka" %% "akka-http-testkit"        % akkaHttpVersion % Test,
    "com.typesafe.akka" %% "akka-actor-testkit-typed" % akkaVersion     % Test,
  )

  lazy val jsonDeps = Seq(
    "io.circe" %% "circe-core"    % circeVersion,
    "io.circe" %% "circe-generic" % circeVersion,
    "io.circe" %% "circe-parser"  % circeVersion,
  )

  lazy val commonDeps = Seq(
    "ch.qos.logback"    % "logback-classic"           % "1.2.3",
    "org.scalatest"     %% "scalatest"                % "3.1.4"         % Test,
  )
}
