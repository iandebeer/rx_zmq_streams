package com.zenaptix.reactive

import java.io.File
import java.nio.file.Paths

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{FileIO, Sink}
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.LazyLogging
import akka.camel.{CamelExtension, CamelMessage, Consumer, Producer}

import scala.concurrent.Await
import scala.concurrent.duration._

/**
  * Created by ian on 2015/06/05.
  *
  */
object ReactivePublisher extends App with LazyLogging {
  implicit val system = ActorSystem("publisher")
  implicit val materializer = ActorMaterializer()
  val camel = CamelExtension(system)
  val camelContext = camel.context
  lazy val log = system.log
  def rxZmq = RxZMQExtension(system)
  val conf = ConfigFactory.load()
  val sourcePath = conf.getString("gateway.source_path")
  val file = Paths.get(sourcePath)

  FileIO
    .fromPath(file)
    .map(b => {
      logger.debug(s"[SOURCE] -> ${b.decodeString("UTF8")}")
      Message(b)
    })
    .to(rxZmq.pubSink())
    .run()
  Await.result(system.whenTerminated, 1000000.seconds)
}
