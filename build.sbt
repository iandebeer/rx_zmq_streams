import sbt._
import sbt.Keys._

val akkaVersion = "2.5.32"

lazy val common = Seq(
  organization := "com.zenaptix",
  version := "0.4.1",
  scalaVersion := "2.13.6",
  scalacOptions ++= Seq("-feature"),
  //bintrayOrganization := Some("zenaptix"),
  //bintrayRepository := "rx_zmq_streams",
  licenses += ("Apache-2.0", url(
    "http://www.apache.org/licenses/LICENSE-2.0")),
  resolvers ++= Seq(
    "Local Maven Repository" at "file://" + Path.userHome.absolutePath + "/.m2/repository",
    "Sonatype Snapshot Repository" at "https://oss.sonatype.org/content/repositories/snapshots"),
   // "zenAptix Snapshot Repository" at "http://zenaptix.net:8081/artifactory/libs-snapshot-local"),
  libraryDependencies ++= Seq(
    "org.scalacheck" %% "scalacheck" % "1.14.2" % "test",
    "org.scalatest" %% "scalatest" % "3.0.8" % "test",
    "com.typesafe.akka" %% "akka-testkit" % akkaVersion % "test",
    "com.typesafe.scala-logging" %% "scala-logging" % "3.9.2",
    "com.typesafe.akka" %% "akka-actor" % akkaVersion,
    "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
    "com.typesafe.akka" %% "akka-agent" % akkaVersion,
    "com.typesafe.akka" %% "akka-camel" % akkaVersion,
    "com.typesafe.akka" %% "akka-stream" % akkaVersion,
    "org.apache.camel" % "camel-core" % "2.24.2" excludeAll ExclusionRule(
      organization = "org.slf4j"),
    //"org.zeromq" % "jzmq" % "3.1.0",
    "org.zeromq" % "jeromq" % "0.5.1",
    "org.json4s" %% "json4s-native" % "3.6.7",
    "ch.qos.logback" % "logback-classic" % "1.2.3"
  ),
  version := version.value
)
lazy val root = (project in file(".")).aggregate(core, subscriber, publisher)

lazy val core = (project in file("core"))
  .settings(common: _*)
  .enablePlugins(JavaAppPackaging, sbtdocker.DockerPlugin)
  .settings(
    crossScalaVersions := Seq("2.11.12", "2.12.10", "2.13.1"),
    name := "rx_zmq_streams"
  )
lazy val subscriber = (project in file("subscriber"))
  .dependsOn(core)
  .settings(common: _*)
  .enablePlugins(JavaAppPackaging, sbtdocker.DockerPlugin)
  .settings(
    crossScalaVersions := Seq("2.11.12", "2.12.10", "12.13.1"),
    name := "rx_zmq_subscriber",
    mainClass in Compile := Some("com.zenaptix.reactive.ReactiveSubscriber"),
    dockerfile in docker := {
      val appDir: File = stage.value
      val targetDir = "/app"
      new sbtdocker.Dockerfile {
        // maintainer("zenAptix","ian@zenaptix.com")
        from("zenlab/jre")
        expose(5556, 5557)
        env("PUBLISHER_IP", "172.17.0.2")
        entryPoint(s"$targetDir/bin/${executableScriptName.value}")
        copy(appDir, targetDir)
      }
    },
    imageNames in docker := Seq(
      ImageName(
        namespace = Some(organization.value),
        repository = name.value,
        tag = Some("v" + version.value)
      )
    ),
    buildOptions in docker := BuildOptions(
      cache = false,
      removeIntermediateContainers = BuildOptions.Remove.Always
    )
  )
lazy val publisher = (project in file("publisher"))
  .dependsOn(core)
  .settings(common: _*)
  .enablePlugins(JavaAppPackaging, sbtdocker.DockerPlugin)
  .settings(
    name := "rx_zmq_publisher",
    crossScalaVersions := Seq("2.11.12", "2.12.10", "2.13.1"),
    mainClass in Compile := Some("com.zenaptix.reactive.ReactivePublisher"),
    dockerfile in docker := {
      val appDir: File = stage.value
      val targetDir = "/app"
      val currentDir = new File(".").getAbsolutePath.dropRight(1)
      new sbtdocker.Dockerfile {
        from("zenlab/jre")
        entryPoint(s"$targetDir/bin/${executableScriptName.value}")
        expose(5556, 5557)
        copy(appDir, targetDir)
      }
    },
    imageNames in docker := Seq(
      ImageName(
        namespace = Some(organization.value),
        repository = name.value,
        tag = Some("v" + version.value)
      )
    ),
    buildOptions in docker := BuildOptions(
      cache = false,
      removeIntermediateContainers = BuildOptions.Remove.Always
    )
  )
