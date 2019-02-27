name := "akka-saga"

version := "0.1.0"

scalaVersion := "2.12.7"

lazy val akkaVersion = "2.5.18"

lazy val httpVersion = "10.1.5"

scalacOptions := Seq("-Ywarn-unused", "-Ywarn-dead-code", "-Ywarn-unused-import")

libraryDependencies ++= Seq(
  "com.typesafe.akka"         %% "akka-actor"                 % akkaVersion,
  "com.typesafe.akka"         %% "akka-persistence"           % akkaVersion,
  "com.typesafe.akka"         %% "akka-cluster-sharding"      % akkaVersion,
  "com.typesafe.akka"         %% "akka-http"                  % httpVersion,
  "com.typesafe.akka"         %% "akka-http-spray-json"       % httpVersion,
  "com.typesafe.akka"         %% "akka-slf4j"                 % akkaVersion,
  "com.typesafe.akka"         %% "akka-persistence-query"     % akkaVersion,
  "com.typesafe.akka"         %% "akka-cluster-tools"         % akkaVersion,
  "com.typesafe.akka"         %% "akka-persistence-cassandra" % "0.91",
  "com.typesafe.akka"         %% "akka-testkit"               % akkaVersion     % "test",
  "com.typesafe.akka"         %% "akka-http-testkit"          % httpVersion     % "test",
  "org.iq80.leveldb"           % "leveldb"                    % "0.10"          % "test",
  "org.fusesource.leveldbjni"  % "leveldbjni-all"             % "1.8"           % "test",
  "org.scalatest"             %% "scalatest"                  % "3.0.5"         % "test"
)

//lazy val app = project in file(".")//.enablePlugins (SbtReactiveAppPlugin, Cinnamon)
mainClass in Compile := Some("com.example.AkkaSagaApp")

//endpoints += TcpEndpoint("cinnamon", 9091, None)

//// Reactive CLI integration for Kubernetes
//enableAkkaClusterBootstrap := true
//endpoints += HttpEndpoint("http", 8080, HttpIngress(Seq(80), Seq("akka-saga.io"), Seq("/")))
//
//annotations := Map(
//  "prometheus.io/scrape" -> "true",
//  "prometheus.io/port" -> "9091"
//)

//libraryDependencies += Cinnamon.library.cinnamonCHMetrics
//libraryDependencies += Cinnamon.library.cinnamonAkka
//libraryDependencies += Cinnamon.library.cinnamonAkkaStream
//libraryDependencies += Cinnamon.library.cinnamonAkkaHttp
//libraryDependencies += Cinnamon.library.cinnamonPrometheus
//libraryDependencies += Cinnamon.library.cinnamonPrometheusHttpServer
//libraryDependencies += Cinnamon.library.cinnamonJvmMetricsProducer
//
//cinnamonLogLevel := "INFO"
//cinnamon in run := true
//cinnamon in test := false

fork := true
