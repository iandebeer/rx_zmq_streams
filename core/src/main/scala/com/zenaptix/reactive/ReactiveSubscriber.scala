package com.zenaptix.reactive

import java.util.Calendar

import akka.actor.SupervisorStrategy.Restart
import akka.actor._
import akka.agent.Agent
import akka.dispatch.{PriorityGenerator, UnboundedPriorityMailbox}
import akka.stream.actor.ActorPublisher
import akka.stream.actor.ActorPublisherMessage.{Cancel, Request}
import akka.util.Timeout
import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.scalalogging.LazyLogging
import org.json4s.native.JsonMethods._
import org.json4s.native.Serialization._
import org.reactivestreams.{Subscriber, Subscription}
import org.zeromq.{ZMQ, ZMsg}
import org.zeromq.ZMQ.{PollItem, Poller}

import scala.annotation.tailrec
import scala.collection.mutable
import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}
import akka.pattern.ask
import akka.pattern.pipe
import akka.util.ByteString

class SubPriorityActorMailbox(settings: ActorSystem.Settings, config: Config)
    extends UnboundedPriorityMailbox(PriorityGenerator {
      case x: Request => 0
      case _ => 3
    })

/**
  *
  */
object ZMQProxyPublisher {

  import scala.concurrent.ExecutionContext.Implicits.global

  def props(zmqContext: ZMQ.Context, connection: String) =
    Props(new ZMQProxyPublisher(zmqContext, connection))
     // .withDispatcher("prio-dispatcher")

  val conf: Config = ConfigFactory.load()
  val highwaterMark = conf.getInt("rx_zmq_stream.highwater_mark")
  val backPressure = Agent(BackPressure(highwaterMark))

}

/**
  *
  * @param zmqContext
  * @param connection
  */
class ZMQProxyPublisher(zmqContext: ZMQ.Context, connection: String)
    extends ActorPublisher[Message]
    with ActorLogging {

  import ZMQProxyPublisher._
  import scala.concurrent.ExecutionContext.Implicits.global

  var subscriber = context.actorOf(
    ZMQSubscriber.props(zmqContext, connection, backPressure),
    name =
      s"_ZMQSubscriber_${connection}_${Calendar.getInstance().getTimeInMillis}")
  var queue: mutable.Queue[Message] = new mutable.Queue[Message]()

  context.system.whenTerminated.foreach(f => {
    log.info(s"${context.self.path.name} shut down!")
    subscriber ! Quit(true)
  })

  override val supervisorStrategy =
    OneForOneStrategy(maxNrOfRetries = -1, withinTimeRange = Duration.Inf) {
      case e: NetworkFailureException =>
        NetworkFailureNotifier.publish(
          connection,
          NetworkFailureNotification(e.getMessage, System.currentTimeMillis()),
          self)
        Restart
      case e: Exception =>
        SupervisorStrategy.stop
    }

  override def receive = {
    case msgQueue: mutable.Queue[Message] =>
      queue.enqueueAll(msgQueue.toIndexedSeq)
      deliverBuf()
    case Request(cnt: Long) =>
      if (cnt != backPressure.get().count) {
        backPressure.alter(BackPressure(cnt))
      }
      deliverBuf()
    case Cancel =>
      //log.info("[CLIENT] : Shutting down")
      while (queue.nonEmpty) {
        deliverBuf()
        Thread.sleep(5)
      }
      log.info(s"${context.self.path.name} shut down!")
      subscriber ! Quit(true)
      Thread.sleep(100)
      self ! PoisonPill
    case a: Any =>
      log.error(s"receive an unsolicited message: $a")
  }

  @tailrec
  final def deliverBuf() {
    if (totalDemand > 0) {
      if (totalDemand <= Int.MaxValue) {
        val (use, keep) = queue.splitAt(totalDemand.toInt)
        queue = keep
        use foreach onNext
      } else {
        val (use, keep) = queue.splitAt(totalDemand.toInt)
        queue = keep
        use foreach onNext
        deliverBuf()
      }
    }
  }
}

/**
  *
  */
object ZMQSubscriber {
  val conf = ConfigFactory.load()
  var timeoutPeriod = conf.getInt("rx_zmq_stream.time_out_period")

  def props(zmqContext: ZMQ.Context,
            connection: String,
            backPressure: Agent[BackPressure]) =
    Props(new ZMQSubscriber(zmqContext, connection, backPressure))
}

/**
  *
  * @param zmqContext
  * @param connection
  * @param backPressure
  */
class ZMQSubscriber(zmqContext: ZMQ.Context,
                    val connection: String,
                    backPressure: Agent[BackPressure])
    extends Actor
    with ActorLogging
    with Subscriber[ControlMessage]
    with ZeroMQProvider with ControlMessages {

  import ZMQSubscriber._

  val controlChannel: ActorRef = context.actorOf(
    Props(new ControlChannel(zmqContext, connection)),
    name = s"_ControlChannel_$connection")
  var dataChannel: Option[ActorRef] = None
  var receivedCount = 0l
  var toReceiveCount = Long.MaxValue

  implicit val timeout = Timeout(timeoutPeriod.seconds)

  import scala.concurrent.ExecutionContext.Implicits.global

  override def preStart() = {
    context.system.scheduler
      .scheduleOnce(1.second, self, Subscribe(cancel = false))
    log.info(s"Starting a new ØMQ Subscriber: ${context.self.path}")
    super.preStart()
  }

  @scala.throws[Exception](classOf[Exception])
  override def postRestart(reason: Throwable): Unit = {
    log.info(
      s"Restarted the ØMQ Subscriber: ${context.self.path} after ${reason.getMessage}")
    super.postRestart(reason)
  }

  override def preRestart(cause: Throwable, msg: Option[Any]): Unit = {
    log.info(
      s"Restarting the ØMQ Subscriber: ${context.self.path} after $cause")
    super.preRestart(cause, msg)
  }

  override def receive: Receive = {
    case sub: Subscribe =>
      controlChannel
        .ask(RequestMsg(Message(ByteString(write(sub)))))
        .onComplete {
          case Success(controlMsg:OnSubscribe) =>
            for (d <- dataChannel) {
              d ! Error
            }
            dataChannel = Some(
              context.actorOf(
                DataChannel.props(
                  zmqContext,
                  connection.split(":").head + s":${controlMsg.port}"),
                name =
                  s"_DataChannel_${connection}_${Calendar.getInstance().getTimeInMillis}"))

            self ! Next(backPressure.get().count)
            toReceiveCount = controlMsg.size
          case Failure(e: Exception) =>
            for (d <- dataChannel) {
              d ! Error(e.getMessage)
            }
        }
    case n: Next =>
      controlChannel
        .ask(RequestMsg(Message(ByteString(write(Next(n.count.toInt))))))
        .onComplete {
          case Success(m) =>
            m match {
              case n: OnNext =>
                onNext(n)
              case a: Any =>
                log.error(s"Received an $a")
            }
          case Failure(e) => onError(e)
        }

    case e: Error =>
      controlChannel
        .ask(RequestMsg(Message(ByteString(write(e)))))
        .onComplete {
          case Success(m) =>
            m match {
              case e: OnError =>
                if (e.recoverable) {
                  onError(ZMQRecoverableError(e))
                } else {
                  onError(ZMQIrrecoverableError(e))
                }
            }
          case Failure(err) =>
            onError(
              ZMQIrrecoverableError(
                OnError(recoverable = false, "error on error")))
        }
    case Quit(b) =>
      onComplete()
  }

  override def onError(e: Throwable) = {
    if (e.isInstanceOf[ZMQIrrecoverableError]) {
      throw new NetworkFailureException
    } else {
      backPressure.alter(BackPressure((backPressure.get.count * 0.8).toInt))
      self ! Next(backPressure.get().count)
    }
  }

  override def onSubscribe(subs: Subscription) = {}

  override def onComplete() = {
    log.info("[CLIENT] : Subscription completed")
    implicit val timeout = Timeout(1000.millis)
    val future = controlChannel ? RequestMsg(
        Message(ByteString(write(Complete(true)))))
    future.onComplete {
      case Success(m) =>
        m match {
          case controlMsg: OnComplete =>
            log.info("[CLIENT] : Completion acknowledged")
            for {
              f2 <- dataChannel.get ? Cancel
              f1 <- controlChannel ? Cancel
            } {
              context.parent ! Cancel
              log.info("[CLIENT] : Shut down")
            }
          case a: Any =>
            log.info("[CLIENT] :WTF")

        }
      case Failure(e) =>
        log.info(
          "[CLIENT] : Completion not acknowledged - shutting down anyway")
        for {
          f1 <- controlChannel ? Cancel
          f2 <- dataChannel.get ? Cancel
        } {
          log.info("[CLIENT] : Shut down")
          context.parent ! Cancel
        }
    }
  }

  override def onNext(next: ControlMessage): Unit = {
    val n = next.asInstanceOf[OnNext]
    if (n.count > 0) {
      dataChannel.get.ask(Next(n.count)).onComplete {
        case Success(data: mutable.Queue[Message @unchecked]) =>
          receivedCount += n.count
          context.parent ! data
          self ! Next(backPressure.get().count)
        case Failure(e:ZMQRecoverableError) =>
          backPressure.alter(BackPressure((backPressure.get.count * .9).toLong))
          val f = controlChannel ? Error(e.error.message)
          f.onComplete {
            case Success(m) =>
              onError(e)
            case Failure(e) =>
              onError(ZMQIrrecoverableError(OnError(recoverable = false, e.getMessage)))
          }
        case _ =>
      }
    } else {
      if (receivedCount >= toReceiveCount) {
        onComplete()
      } else {
        self ! Next(backPressure.get().count)
      }
    }
  }
}

/**
  *
  */
object ControlChannel {
  def props(zmqContext: ZMQ.Context, connection: String): Props =
    Props(new ControlChannel(zmqContext, connection))
  val conf = ConfigFactory.load()
  val timeoutPeriod = conf.getInt("rx_zmq_stream.time_out_period")
}

/**
  *
  * @param zmqContext
  * @param socket
  */
class ControlChannel(zmqContext: ZMQ.Context, socket: String)
    extends FSM[State, Data]
    with ActorLogging with ControlMessages {
  import ControlChannel._

  import scala.concurrent.ExecutionContext.Implicits.global

  implicit val timeout = Timeout(timeoutPeriod.millis)

  startWith(Connecting, Uninitialized)

  when(Connecting) {
    case Event(request: RequestMsg, Uninitialized) =>
      val connection =
        Connection(zmqContext, s"tcp://$socket", timeoutPeriod)
      val promise = Promise[ControlMessage]()
      promise.future pipeTo sender()
      connection.awaitResponse(request, timeoutPeriod) match {
        case Failure(e) =>
          fail(Some(promise), e)
        case Success(subs) =>
          promise.success(subs)
          goto(Consuming) using connection
      }
  }

  when(Consuming) {
    case Event(request: RequestMsg, connection: Connection) =>
      val promise = Promise[ControlMessage]()
      promise.future pipeTo sender()
      connection.awaitResponse(request, timeoutPeriod) match {
        case Success(msg) =>
          promise.success(msg)
        case Failure(e) =>
          promise.failure(e)
      }
      stay() using connection

    case Event(e: Error, connection: Connection) =>
      val promise = Promise[ControlMessage]()
      promise.future pipeTo sender()
      val request = RequestMsg(Message(ByteString(write(e))))
      connection.awaitResponse(request, timeoutPeriod) match {
        case Success(msg) =>
          promise.success(msg)
        case Failure(err) =>
          promise.failure(err)
      }
      stay() using connection

    case Event(Cancel, connection: Connection) =>
      val promise = Promise[Boolean]()
      promise.future pipeTo sender()
      connection.close()
      promise.success(true)
      stop()
  }

  initialize()

  def fail(promise: Option[Promise[ControlMessage]], e: Throwable) = {
    for (p <- promise) { p.failure(e) }
    NetworkFailureNotifier.publish(
      socket,
      NetworkFailureNotification(e.getMessage, System.currentTimeMillis()),
      self)
    context.parent ! Subscribe(cancel = false)
    goto(Connecting) using Uninitialized
  }
}

/**
  *
  */
object DataChannel {
  def props(zmqContext: ZMQ.Context, connection: String): Props =
    Props(new DataChannel(zmqContext, connection: String))
}

/**
  *
  * @param zmqContext
  * @param connection
  */
class DataChannel(zmqContext: ZMQ.Context, connection: String)
    extends Actor
    with ActorLogging with ControlMessages {

  import akka.pattern.pipe
  import scala.concurrent.ExecutionContext.Implicits.global

  val queue: mutable.Queue[Message] = new mutable.Queue[Message]()
  val conf = ConfigFactory.load()
  var timeoutPeriod = conf.getInt("rx_zmq_stream.time_out_period")
  val subscriber = zmqContext.socket(ZMQ.SUB)
  subscriber.connect(s"tcp://$connection")
  subscriber.subscribe("".getBytes)
  val highWaterMark = conf.getLong("rx_zmq_stream.highwater_mark")
  //subscriber.setHWM(highWaterMark/2)

  implicit val timeout = Timeout(timeoutPeriod.millis)

  override def receive = {
    case Cancel =>
      val promise = Promise[Boolean]()
      promise.future pipeTo sender()
      cancel()
      promise.success(true)

    case n: Next => request(n.count)
  }
  def cancel() = {
    subscriber.setLinger(100)
    subscriber.close()
    log.info("CLIENT: Closing data channel connection")
    Thread.sleep(5)
  }

  def request(l: Long) = {
    val promise = Promise[mutable.Queue[Message]]()
    val queue: mutable.Queue[Message] = new mutable.Queue[Message]()
    val timer = System.currentTimeMillis() + timeoutPeriod
    promise.future pipeTo sender()
    while (queue.size < l && System.currentTimeMillis() < timer) {
      for (m <- receiveMessage()) yield queue.enqueue(m)
    }
    if (queue.size == l) {
      promise.success(queue)
    } else {
      promise.failure(ZMQRecoverableError(OnError(recoverable = true, message = s"Requested $l messages -  only received ${queue.size}")))
    }
  }

  @tailrec
  final def receiveMessage(
      currentFrames: Vector[ByteString] = Vector.empty): Option[Message] = {
    subscriber.recv(ZMQ.NOBLOCK) match {
      case null => None
      case bytes =>
        val frames = currentFrames :+ ByteString(bytes)
        if (subscriber.hasReceiveMore) {
          receiveMessage(frames)
        } else {
          Some(Message(frames: _*))
        }
    }
  }
}

/**
  *
  * @param zmqContext
  * @param connection
  * @param timeoutPeriod
  */
case class Connection(zmqContext: ZMQ.Context,
                      connection: String,
                      timeoutPeriod: Int)
    extends Data
    with LazyLogging with ControlMessages  {

  val requester: ZMQ.Socket = zmqContext.socket(ZMQ.REQ)
  requester.connect(connection)
 // val pollItems = Array(new PollItem(requester, Poller.POLLIN))
  val poller: ZMQ.Poller = zmqContext.poller(1)
  poller.register(requester, Poller.POLLIN)

  def awaitResponse(request: RequestMsg, period: Long): Try[ControlMessage] = {

    if (requester.send(request.msg.head.toArray, 0)) {
      val response = poller.poll(period)
      if (response > -1) {
        val  msg = ZMsg.recvMsg(requester)
        Success(
          parse(ByteString(msg.getLast.getData).decodeString("UTF8"))
            .extract[ControlMessage])
      } else {
        Failure(new PollInterruptedException())
      }
    } else {
      Failure(new PollInterruptedException())
    }
  }

  def close(): Unit = {
    logger.info("CLIENT: Closing control channel connection")
    requester.setLinger(10)
    requester.close()
  }

  def fail(mode: String): Failure[ControlMessage] = {
    logger.error(s"FAILED NETWORK CONNECTION ON ${mode.toUpperCase}")
    requester.close()
    Failure(new NetworkFailureException())
  }
}

/**
  *
  */
sealed trait State

/**
  *
  */
case object Connecting extends State

/**
  *
  */
case object Consuming extends State