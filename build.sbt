name := "akka-saga"

version := "0.1.0"

scalaVersion := "2.12.7"

lazy val akkaVersion = "2.5.21"

lazy val httpVersion = "10.1.7"

scalacOptions := Seq("-Ywarn-unused", "-Ywarn-dead-code", "-Ywarn-unused-import")

libraryDependencies ++= Seq(
  "com.typesafe.akka"             %% "akka-actor"                        % akkaVersion,
  "com.typesafe.akka"             %% "akka-persistence"                  % akkaVersion,
  "com.typesafe.akka"             %% "akka-cluster-sharding"             % akkaVersion,
  "com.typesafe.akka"             %% "akka-http"                         % httpVersion,
  "com.typesafe.akka"             %% "akka-http-spray-json"              % httpVersion,
  "com.typesafe.akka"             %% "akka-slf4j"                        % akkaVersion,
  "com.typesafe.akka"             %% "akka-persistence-query"            % akkaVersion,
  "com.typesafe.akka"             %% "akka-persistence-cassandra"        % "0.91",
  "com.typesafe.akka"             %% "akka-testkit"                      % akkaVersion % "test",
  "com.typesafe.akka"             %% "akka-http-testkit"                 % httpVersion % "test",
  "org.iq80.leveldb"               % "leveldb"                           % "0.10"      % "test",
  "org.fusesource.leveldbjni"      % "leveldbjni-all"                    % "1.8"       % "test",
  "org.scalatest"                 %% "scalatest"                         % "3.0.5"     % "test"
)

mainClass in Compile := Some("com.example.AkkaSagaApp")

fork := true

enablePlugins(AkkaGrpcPlugin)
enablePlugins(JavaAgent)
javaAgents += "org.mortbay.jetty.alpn" % "jetty-alpn-agent" % "2.0.9" % "runtime;test"
