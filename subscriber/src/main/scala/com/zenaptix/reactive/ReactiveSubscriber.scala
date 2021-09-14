package com.zenaptix.reactive

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.Await
import scala.concurrent.duration._

/**
  * Created by ian on 2015/06/05.
  *
  */
object ReactiveSubscriber extends App with LazyLogging {
  implicit val system = ActorSystem("subscriber")
  implicit val materializer = ActorMaterializer()
  def rxZmq = RxZMQExtension(system)
  val conf = ConfigFactory.load()
  val connection = conf.getString("zeromq.host") + ":" + conf.getInt(
        "zeromq.port")
  rxZmq
    .subSource(connection)
    .map(m => logger.debug(s"SINK <- ${m.head.decodeString("UTF8")}"))
    .runWith(Sink.ignore)
  Await.result(system.whenTerminated, 1000000.second)
}
