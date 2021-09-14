package com.zenaptix.reactive

/**
  * Created by ian on 2015/03/16.
  *
  */

import akka.actor._
import akka.agent.Agent
import com.typesafe.config.ConfigFactory
import org.reactivestreams.{Publisher, Subscriber, Subscription}
import akka.stream.actor.{ActorSubscriber, MaxInFlightRequestStrategy, RequestStrategy}
import akka.util.ByteString
import org.zeromq.{ZMQ, ZMsg}
import org.json4s.native.JsonMethods._
import org.json4s.native.Serialization._
import org.zeromq.ZMQ.{PollItem, Poller}

import scala.concurrent.duration._
import scala.collection.mutable
import com.typesafe.scalalogging.LazyLogging


object ZMQPublisher {
  val conf = ConfigFactory.load()
  def props(zmqContext: ZMQ.Context, count: Long, port: Option[Int]) = {
    Props(
        new ZMQPublisher(zmqContext,
                         port.getOrElse(conf.getInt("zeromq.port")),
                         count))
  }
}

class ZMQPublisher(zmqContext: ZMQ.Context,
                   port: Int,
                   count: Long = Long.MaxValue)
    extends ActorSubscriber
    with ActorLogging
    with Publisher[Long] {
  val conf = ConfigFactory.load()

  val MaxRetries = conf.getInt("rx_zmq_stream.max-retries")
  val MaxQueueSize = conf.getInt("rx_zmq_stream.highwater_mark")
  var queue: mutable.Queue[Message] = new mutable.Queue[Message]()
  val connection = s"tcp://0.0.0.0:$port"
  val controlChannel =
    ZMQControllChannelServer(self, zmqContext, connection, port, count)
  val pubChannel = context.actorOf(ZMQPublisherProxy.props(zmqContext, port),
                                   name = s"_ZMQPublisherProxy_$port")
  var previousSent = 0l
  var isComplete = false
  var retries = 0
  var batch: Option[mutable.Queue[Message]] = None
  var totalSent = 0l
  import scala.concurrent.ExecutionContext.Implicits.global
  context.system.whenTerminated.foreach(f => {
    pubChannel ! Quit(true)
    controlChannel.alive = false
    log.info(s"${context.self.path.name} shut down!")
  })

  override def preStart() = {
    log.debug(s"Starting a new ZeroMQ Publisher: ${context.self.path}")
    super.preStart()
  }

  override def receive: Receive = {
    case s: Subscribe =>
      previousSent = 0
      controlChannel.onSubscribe(count - totalSent)
    case n: Next =>
      if (totalSent < count) {
        queue = queue.drop(previousSent.toInt)
        totalSent += previousSent
        val availableToSend =
          Math.min(queue.size, count - totalSent).min(n.count)
        batch = Some(queue.splitAt(availableToSend.toInt)._1)
        controlChannel.onNext(availableToSend)
        previousSent = availableToSend
      } else {
        controlChannel.onNext(0)
      }
      if (batch.get.nonEmpty) {
        pubChannel ! batch.get
      }
    case c: Complete =>
      controlChannel.onComplete()
      pubChannel ! Quit(true)
      Thread.sleep(100)
      log.info(s"Shutting down  ${context.self.path.name}")
      self ! PoisonPill

    case e: Error =>
      retries += 1
      previousSent = 0
      log.info(s"[SERVER] - Client: ${e.message}. Retry!")
      if (retries < MaxRetries) {
        controlChannel.onError(
            ZMQRecoverableError(
                OnError(recoverable = true, message = "resending data")))
      } else {
        controlChannel.onError(
            ZMQIrrecoverableError(
                OnError(recoverable = false,
                        message =
                          s"[SERVER] - Exceeds maximum retries: $MaxRetries")))
      }

    case akka.stream.actor.ActorSubscriberMessage.OnNext(msg: Message) =>
      queue.enqueue(msg)

    case akka.stream.actor.ActorSubscriberMessage.OnError(error: Throwable) =>
      log.error(
          s"Received an upstream error ${error.getMessage}\n${error.getStackTrace}")

    case akka.stream.actor.ActorSubscriberMessage.OnComplete =>
      isComplete = true
    case a: Any =>
      log.error(s"Received an unsolicited ${a.getClass}")
  }

  override protected def requestStrategy: RequestStrategy = {
    new MaxInFlightRequestStrategy(max = MaxQueueSize) {
      override def inFlightInternally: Int = {
        queue.size
      }
    }
  }

  override def subscribe(subscriber: Subscriber[_ >: Long]): Unit = {
    //for now there is only 1 subscriber: ZMQControllChannelServer
  }
}

case class ZMQControllChannelServer(publisher: ActorRef,
                                    zmqContext: ZMQ.Context,
                                    connection: String,
                                    port: Int,
                                    count: Long)
    extends Subscriber[Long]
    with LazyLogging with ControlMessages {

  var alive = true
  val server: ZMQ.Socket = zmqContext.socket(ZMQ.REP)
  val pollItems = Array(new PollItem(server, Poller.POLLIN))
  server.bind(connection)
  val poller: ZMQ.Poller = zmqContext.poller(1)
  poller.register(server, Poller.POLLIN)
  new Thread {
    override def run() {
      while (alive) {
       // val response = ZMQ.poll(selector,pollItems, 10)
        val response = poller.poll(10)
        if (response > -1 ) {
          val  m = ZMsg.recvMsg(server)
          val msg = parse(ByteString(m.getLast.getData).decodeString("UTF8")).extract[ControlMessage]
          logger.debug(s"[SERVER] <- CONTROL:  $msg")
          msg match {
            case e: Error => publisher ! e
            case s: Subscribe => publisher ! s
            case n: Next => publisher ! n
            case c: Complete => publisher ! c
          }
        } else {
          Thread.sleep(1)
        }
      }
    }
  }.start()


  override def onError(throwable: Throwable): Unit = {
    throwable match {
      case error: ZMQRecoverableError =>
        send(OnError(recoverable = true, message = throwable.getMessage))
      case _ =>
        send(OnError( message = throwable.getMessage))
    }
  }

  override def onSubscribe(subs: Subscription): Unit = {
    //send(OnSubscribe(size = count, port = ZMQPublisher.port + 1))
  }

  def onSubscribe(count: Long): Unit = {
    send(OnSubscribe(size = count, port = port + 1))
  }

  override def onComplete(): Unit = {
    alive = false
    send(OnComplete())
    Thread.sleep(100)
    server.setLinger(0)
    server.close()
    logger.info("[SERVER]: Closing control channel")

  }

  override def onNext(l: Long): Unit = {
    send(OnNext(l))
  }

  def send(msg: ControlMessage): Unit = {
    val m = write(msg)
    logger.debug(s"[SERVER] -> CONTROL: $msg  ")
    server.send(m)
  }
}

object ZMQPublisherProxy {
  def props(zmqContext: ZMQ.Context, port: Int): Props =
    Props(new ZMQPublisherProxy(zmqContext, port))

  /**
    *
    * @param zmqContext
    * @param port
    */
  class ZMQPublisherProxy(zmqContext: ZMQ.Context, port: Int)
    extends Actor
      with ActorLogging {

    import scala.concurrent.ExecutionContext.Implicits.global

    val queueAgent = Agent(QueueAgent())

    override def preStart() = {
      log.info(s"Starting a new ØMQ PublisherProxy : ${context.self.path}")
      context.system.scheduler.scheduleOnce(100.millis, self, SendMessages)
      super.preStart()
    }

    val publisher = zmqContext.socket(ZMQ.PUB)
    publisher.bind(s"tcp://*:${port + 1}")

    override def receive = {
      case Quit(b) =>
        log.info("[SERVER]: Closing data channel")
        publisher.setLinger(10)
        publisher.close()

      case messages: mutable.Queue[Message@unchecked] =>
        queueAgent.send(QueueAgent(queueAgent.get.queue.++=(messages)))
      case SendMessages =>
        queueAgent.get.queue.dequeueAll(m => true).map(m => publisher.send(m.head.toArray, 0))
        self ! SendMessages
      case a: Any => log.error(s"controller got $a")
    }
  }

  /**
    *
    * @param queue
    */
  case class QueueAgent(
                         queue: collection.mutable.Queue[Message] =
                         collection.mutable.Queue[Message]())

}
