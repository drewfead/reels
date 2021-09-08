ThisBuild / scalaVersion := "2.13.4"
ThisBuild / scalacOptions ++= Seq(
  "-unchecked",
  "-feature",
  "-deprecation",
  "-Xfatal-warnings",
  "-language:higherKinds",
  "-Xlint:_,-byname-implicit", // by-name implicit warnings break circe codec derivation
  "-Ywarn-unused:implicits",
  "-Ywarn-unused:imports",
  "-Ywarn-unused:locals",
  "-Ywarn-unused:privates",
  "-Ymacro-annotations",
  "-Yrangepos",
  "-Xsource:3",
)

ThisBuild / javacOptions ++= Seq("-source", "1.8", "-target", "1.8")
ThisBuild / scalacOptions ++= Seq("-target:8")

lazy val `core-lib` = project
  .in(file("lib/core"))

lazy val `dynamo-lib` = project
  .in(file("lib/dynamo"))

lazy val `catalog-svc` = project
  .in(file("svc/catalog"))
  .dependsOn(
    `core-lib` % "test->compile;compile->compile",
    `dynamo-lib` % "test->compile;compile->compile",
  )

lazy val `credits-svc` = project
  .in(file("svc/credits"))
  .dependsOn(
    `core-lib` % "test->compile;compile->compile",
  )

lazy val `backfill-bin` = project
  .in(file("bin/backfill"))
  .dependsOn(
    `core-lib` % "test->compile;compile->compile",
  )

lazy val root = (project in file("."))
  .aggregate(
    `core-lib`,
    `dynamo-lib`,
    `catalog-svc`,
    `credits-svc`,
    `backfill-bin`
  )
