import Dependencies._

ThisBuild / organization := "com.reels"
ThisBuild / test / testOptions += Tests.Argument("-oDF")
ThisBuild / libraryDependencies ++= akkaDeps
ThisBuild / libraryDependencies ++= jsonDeps
ThisBuild / libraryDependencies ++= Seq(
  "com.lightbend.akka" %% "akka-stream-alpakka-dynamodb" % "3.0.3",
  "io.bullet" %% "borer-core" % "1.7.2",
  "io.bullet" %% "borer-compat-circe" % "1.7.2",
)
ThisBuild / compile/ mainClass := Some("com.reels.catalog.Server")
ThisBuild / run / mainClass := Some("com.reels.catalog.Server")