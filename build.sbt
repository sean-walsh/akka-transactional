name := "akka-transactional"

version := "0.1.0"

scalaVersion := "2.13.4"

lazy val akkaVersion = "2.6.20"
lazy val akkaHttpVersion = "10.2.10"
lazy val GrpcVersion = "2.1.6"

scalacOptions := Seq("-Ywarn-unused", "-Ywarn-dead-code")

enablePlugins(AkkaGrpcPlugin)

libraryDependencies ++= Seq(
  "com.typesafe.akka"             %% "akka-actor-typed"                  % akkaVersion,
  "com.typesafe.akka"             %% "akka-persistence-typed"            % akkaVersion,
  "com.typesafe.akka"             %% "akka-cluster-sharding-typed"       % akkaVersion,
  "com.typesafe.akka"             %% "akka-http"                         % akkaHttpVersion,
  "com.typesafe.akka"             %% "akka-http2-support"                % akkaHttpVersion,
  "com.typesafe.akka"             %% "akka-stream"                       % akkaVersion,
  "com.typesafe.akka"             %% "akka-discovery"                    % akkaVersion,
  "com.typesafe.akka"             %% "akka-pki"                          % akkaVersion,

  // The Akka HTTP overwrites are required because Akka-gRPC depends on 10.1.x
  "com.typesafe.akka"             %% "akka-http"                         % akkaHttpVersion,
  "com.typesafe.akka"             %% "akka-http2-support"                % akkaHttpVersion,

  "ch.qos.logback"                 % "logback-classic"                   % "1.4.1",
  "com.typesafe.akka"             %% "akka-serialization-jackson"        % akkaVersion,
  "com.typesafe.akka"             %% "akka-actor-testkit-typed"          % akkaVersion      % Test,
  "com.typesafe.akka"             %% "akka-stream-testkit"               % akkaVersion      % Test,
//  "org.iq80.leveldb"               % "leveldb"                           % "0.10"           % Test,
//  "org.fusesource.leveldbjni"      % "leveldbjni-all"                    % "1.8"            % Test,
  "org.scalatest"                 %% "scalatest"                         % "3.1.1"          % Test
)

fork := true
