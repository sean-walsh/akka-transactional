name := "akka-transactional"

version := "0.1.0"

scalaVersion := "2.12.7"

lazy val akkaVersion = "2.6.17"
lazy val akkaHttpVersion = "10.2.6"
lazy val GrpcVersion = "2.1.0"

scalacOptions := Seq("-Ywarn-unused", "-Ywarn-dead-code", "-Ywarn-unused-import")

enablePlugins(AkkaGrpcPlugin)

libraryDependencies ++= Seq(
  "com.typesafe.akka"             %% "akka-actor-typed"                  % akkaVersion,
  "com.typesafe.akka"             %% "akka-persistence-typed"            % akkaVersion,
  "com.typesafe.akka"             %% "akka-cluster-sharding-typed"       % akkaVersion,
  "com.typesafe.akka"             %% "akka-slf4j"                        % akkaVersion,
  "com.typesafe.akka"             %% "akka-persistence-query"            % akkaVersion,
  "com.typesafe.akka"             %% "akka-persistence-cassandra"        % "0.91",
  "com.typesafe.akka"             %% "akka-http"                         % akkaHttpVersion,
  "com.typesafe.akka"             %% "akka-http2-support"                % akkaHttpVersion,
  "com.typesafe.akka"             %% "akka-stream"                       % akkaVersion,
  "com.typesafe.akka"             %% "akka-discovery"                    % akkaVersion,
  "com.typesafe.akka"             %% "akka-pki"                          % akkaVersion,
  // The Akka HTTP overwrites are required because Akka-gRPC depends on 10.1.x
  "com.typesafe.akka"             %% "akka-http"                         % akkaHttpVersion,
  "com.typesafe.akka"             %% "akka-http2-support"                % akkaHttpVersion,
  "com.typesafe.akka"             %% "akka-actor-testkit-typed"          % akkaVersion      % Test,
  "com.typesafe.akka"             %% "akka-stream-testkit"               % akkaVersion      % Test,
  "org.iq80.leveldb"               % "leveldb"                           % "0.10"           % Test,
  "org.fusesource.leveldbjni"      % "leveldbjni-all"                    % "1.8"            % Test,
  "org.scalatest"                 %% "scalatest"                         % "3.1.1"          % Test
)

fork := true
