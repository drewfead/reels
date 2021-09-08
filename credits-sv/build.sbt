val Http4sVersion = "0.21.16"
val CirceVersion = "0.13.0"
val MunitVersion = "0.7.20"
val LogbackVersion = "1.2.3"
val Log4CatsVersion = "2.1.0"
val MunitCatsEffectVersion = "0.13.0"
val EnumeratumVersion = "1.6.1"
val RiakClientVersion = "2.0.6"

lazy val root = (project in file("."))
  .settings(
    organization := "com.reels",
    name := "credits-sv",
    version := "0.0.1-SNAPSHOT",
    scalaVersion := "2.13.4",
    libraryDependencies ++= Seq(
      "org.http4s"      %% "http4s-ember-server" % Http4sVersion,
      "org.http4s"      %% "http4s-ember-client" % Http4sVersion,
      "org.http4s"      %% "http4s-circe"        % Http4sVersion,
      "org.http4s"      %% "http4s-dsl"          % Http4sVersion,
      "io.circe"        %% "circe-generic"       % CirceVersion,
      "com.basho.riak"  %  "riak-client"         % RiakClientVersion,
      "org.scalameta"   %% "munit"               % MunitVersion           % Test,
      "org.typelevel"   %% "munit-cats-effect-2" % MunitCatsEffectVersion % Test,
      "ch.qos.logback"  %  "logback-classic"     % LogbackVersion,
      "com.beachape"    %% "enumeratum"          % EnumeratumVersion,
      "org.scalameta"   %% "svm-subs"            % "20.2.0",
    ),
    addCompilerPlugin("org.typelevel" %% "kind-projector"     % "0.10.3"),
    addCompilerPlugin("com.olegpy"    %% "better-monadic-for" % "0.3.1"),
    testFrameworks += new TestFramework("munit.Framework")
  )
