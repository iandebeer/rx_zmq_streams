package com.zenaptix.reactive

//import java.io.File

import java.util.concurrent.atomic.AtomicInteger

import akka.actor._
import akka.stream._
import akka.stream.scaladsl.{Sink, Source}
import akka.testkit.TestKit
import akka.util.Timeout
import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.scalalogging.LazyLogging
import org.scalatest.{FlatSpecLike, Matchers}
import akka.util.ByteString

import scala.concurrent.duration._

/**
  * Created by Ian de Beer
  * on 2015/04/15.
  * for zenAptix (Pty) Ltd
  */
class RxZMQExtensionSpec
    extends TestKit(ActorSystem("RxZMQExtensionSpec", RxZMQConfig.configs))
    with FlatSpecLike
    with Matchers
    with LazyLogging {
  val count = 1000000
  val timer = 20000 //count / 5
  implicit val timeout: Timeout = Timeout(timer.millis)
  implicit val materializer = ActorMaterializer(
    ActorMaterializerSettings(system).withMaxFixedBufferSize(2048))

  val conf = RxZMQConfig.configs

  def rxZmq = RxZMQExtension(system)

  "A RxZMQExtension " should "provide a pubSink that will transmit its data from an Akka Stream onto zeroMQ" in {}
  it should "provide a subSource that will receive data over zeroMQ and stream it out to a subsequent Akka Stream" in {}

  it should "transmit the data and allow for a back-pressure on the zeroMQ transmission" in {
    //create a test source for the Akka Stream from a file
    val startTime = System.currentTimeMillis
    val workerCount = 4
    val baseSocket = 5556

    val counters = Array.fill(workerCount)(new AtomicInteger(0))
    val controller = system.actorOf(Supervisor.props())
    val source = Source(1 to count).async.map(b => {
      logger.debug(s"[SOURCE] -> Testing $b")
      Message(ByteString(s"Testing $b"))
    })
    val balancedPubSink = rxZmq.loadBalancedPubSink(source, workerCount)

    for (i <- 0 until workerCount) {
      val connection = s"0.0.0.0:${baseSocket + { i * 10 }}"
      NetworkFailureNotifier.subscribe(controller, connection)
      rxZmq
        .subSource(connection)
        .map(
          m => {
            counters(i).getAndIncrement()
            logger.debug(s"[SINK$i]] <- ${m.head.utf8String}")
          }
        )
        .via(balancedPubSink.sharedKillSwitch.flow)
    }.runWith(Sink.ignore)

    balancedPubSink.pubSinks.run

    new Thread() {
      while (counters.foldLeft(0)((b, a) => b + a.get()) < count && System.currentTimeMillis - startTime < timer) {
        Thread.sleep(1000)
      }
      balancedPubSink.sharedKillSwitch.shutdown()
    }.run()

    counters.foldLeft(0)((b, a) => b + a.get()) shouldBe count

    logger.info(s"\n\n************************************************************************\n* $count records sent in ${(System
      .currentTimeMillis() - startTime) / 1000.0} seconds -> ${count / ((System
      .currentTimeMillis() - startTime) / 1000.0)} / second\n************************************************************************\n")
    Thread.sleep(1000)
    materializer.shutdown()
  }
}

object RxZMQConfig {
  val confS =
    """
                zeromq {
                |  host = "0.0.0.0"
                |  port = 5556
                |  block_size = 10
                |  block_timeout_seconds = 1
                |}
                |gateway {
                |  source_path = "data/test.txt"
                |}
                |rx_zmq_stream {
                |  time_out_period = 250
                |  highwater_mark = 2048
                |  max-retries = 100
                |}
                |prio-dispatcher {
                |  mailbox-type = "com.zenaptix.reactive.SubPriorityActorMailbox"
                |}
                |akka {
                |  loggers = ["akka.event.slf4j.Slf4jLogger"]
                |  logging-filter = "akka.event.slf4j.Slf4jLoggingFilter"
                |  log-dead-letters = false
                |  akka.log-dead-letters-during-shutdown = false
                |  loglevel = "DEBUG"
                |  stream {
                |    materializer {
                |      initial-input-buffer-size = 128
                |      max-input-buffer-size = 1024
                |       subscription-timeout {
                |        # when the subscription timeout is reached one of the following strategies on
                |        # the "stale" publisher:
                |        # cancel - cancel it (via `onError` or subscribing to the publisher and
                |        #          `cancel()`ing the subscription right away
                |        # warn   - log a warning statement about the stale element (then drop the
                |        #          reference to it)
                |        # noop   - do nothing (not recommended)
                |        mode = cancel
                |
                |        # time after which a subscriber / publisher is considered stale and eligible
                |        # for cancelation (see `akka.stream.subscription-timeout.mode`)
                |        timeout = 1s
                |      }
                |    }
                |  }
                |}
              """.stripMargin
  val configs = ConfigFactory.parseString(confS)

  def config(configs: String*): Config =
    configs.foldLeft(ConfigFactory.empty)((r, c) ⇒
      r.withFallback(ConfigFactory.parseString(c)))
}

object Supervisor {
  def props() = Props(new Supervisor())
}
class Supervisor extends Actor with ActorLogging {
  override def receive = {
    case notification: NetworkFailureNotification =>
      println(s"\n\nFailure: $notification\n")
  }
}
