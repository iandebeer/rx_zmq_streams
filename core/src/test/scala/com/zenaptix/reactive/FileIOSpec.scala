package com.zenaptix.reactive

import java.nio.file.Paths

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, IOResult}
import akka.stream.scaladsl.{FileIO, Sink}
import com.typesafe.scalalogging.LazyLogging
import org.scalatest.{Matchers, WordSpec}

import scala.concurrent.duration._
import scala.concurrent.Future
import scala.language.postfixOps

/**
  * Created by ian on 2016/05/31.
  */
class FileIOSpec extends WordSpec with Matchers with LazyLogging {
  val file = Paths.get("/Users/ian/dev/rx_zmq_streams_public/data/test.txt")
  implicit val system = ActorSystem("publisher")
  implicit val timeout: Duration = 5.seconds
  implicit val materializer = ActorMaterializer()

  "A File" when {
    "read" should {
      "write" in {
        val foreach: Future[IOResult] = FileIO
          .fromPath(file)
          .map(b => {
            val m = Message(b)
            logger.debug(s"[SOURCE] -> ${m}")
          })
          .to(Sink.ignore)
          .run()
      }
    }
  }
}
