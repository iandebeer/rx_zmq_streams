package com.zenaptix.reactive

import akka.NotUsed
import akka.actor._
import akka.stream.{ClosedShape, KillSwitches, SharedKillSwitch}
import akka.stream.scaladsl.{Balance, Broadcast, Flow, GraphDSL, RunnableGraph, Sink, Source}
import com.typesafe.config.ConfigFactory
import org.zeromq.ZMQ

/**
  * Created by ian on 2015/04/14.
  * 
  */
/**
  * ReactiveZeroMQ Publisher Subscriber  Extension companion
  */
object RxZMQExtension
    extends ExtensionId[RxZMQExtension]
    with ExtensionIdProvider {
  val conf = ConfigFactory.load()
  def lookup() = RxZMQExtension
  val threads = conf.getInt("zeromq.threads")
  val zmqContext =  ZMQ.context(threads)

  override def createExtension(system: ExtendedActorSystem): RxZMQExtension =
    new RxZMQExtension(system)
}

/**
  * ReactiveZeroMQ Publisher Subscriber Extension that provides a:
  * 1. pubSink that will transmit its data from an Akka Stream onto zeroMQ
  * 2. subSource that will receive data over zeroMQ and stream it out to a subsequent Akka Stream
  * 3. the pubSink and subSource are implementing the Reactive Streams protocol and as such allows
  *    for back-pressure on the zeroMQ transmission
  * It uses the mDialog/scala-zeromq extension for Akka
  * @param system Actor System
  */
class RxZMQExtension(val system: ActorSystem) extends Extension {
   import RxZMQExtension._

  /**
    * An implementation of the Reactive Stream Publisher protocol
    * that is both a zeroMQ Pub socket and a Akka Streams Sink (subscriber)
    * @param count specifiy the number of records available to send e.g. number of records in a sql table
    * @return
    */
  def pubSink(count: Long = Long.MaxValue, port:Option[Int] = Some(5556)): Sink[Message, ActorRef] =
    Sink.actorSubscriber(ZMQPublisher.props(zmqContext, count, port))

  /**
    * An implementation of the Reactive Stream Subscriber protocol
    * that is both a zeroMQ Sub socket and a Akka Streams Source (publisher)
    */
  def subSource(connection: String): Source[Message, ActorRef] = {
    Source.actorPublisher(ZMQProxyPublisher.props(zmqContext,connection))
  }

  /**
    *
    * @param source
    * @param workerCount
    * @param baseSocket
    * @return
    */
  def loadBalancedPubSink(source:Source[Any,NotUsed]#Repr[Message],  workerCount:Int, baseSocket:Int = 5556) = {
    val sharedKillSwitch = KillSwitches.shared("kill-switch")
    BalancedPubSink(RunnableGraph.fromGraph(GraphDSL.create() { implicit b =>
      import GraphDSL.Implicits._
      val balance = b.add(Balance[Message](workerCount,waitForAllDownstreams=true))
      source ~> balance.in
      for { i <- 0 until workerCount } yield {
        balance.out(i) ~> Flow[Message] ~> pubSink(port = Some(baseSocket + (i * 10)))
      }
      ClosedShape
    }),sharedKillSwitch)
  }

  /**
    *
    * @param source
    * @param workerCount
    * @param baseSocket
    * @return
    */
  def broadcastPubSink(source:Source[Any,NotUsed]#Repr[Message],  workerCount:Int, baseSocket:Int = 5556) = {
    val sharedKillSwitch = KillSwitches.shared("kill-switch")
    BalancedPubSink(RunnableGraph.fromGraph(GraphDSL.create() { implicit b =>
      import GraphDSL.Implicits._
      val broadcast = b.add(Broadcast[Message](workerCount))
      source ~> broadcast.in
      for { i <- 0 until workerCount } yield {
        broadcast.out(i) ~> Flow[Message] ~> pubSink(port = Some(baseSocket + (i * 10)))
      }
      ClosedShape
    }),sharedKillSwitch)
  }
}

case class BalancedPubSink(pubSinks:RunnableGraph[NotUsed],sharedKillSwitch:SharedKillSwitch)
case class BroadcastPubSink(pubSinks:RunnableGraph[NotUsed],sharedKillSwitch:SharedKillSwitch)

trait ZeroMQProvider {
  val connection: String
  val controlChannel: ActorRef
}
