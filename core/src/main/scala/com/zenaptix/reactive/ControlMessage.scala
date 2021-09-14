package com.zenaptix.reactive

import akka.util.ByteString
import org.json4s.ShortTypeHints
import org.json4s.native.Serialization

import scala.collection.mutable.ArrayBuffer
import scala.collection.{IndexedSeq, mutable}

/**
  * Created by ian on 2015/03/13.
  *
  */
trait ControlMessages {
  implicit val formats = Serialization.formats(
    ShortTypeHints(
      List(classOf[Subscribe],
        classOf[OnSubscribe],
        classOf[Next],
        classOf[OnNext],
        classOf[OnComplete],
        classOf[Complete],
        classOf[Error],
        classOf[OnError])))
}

/**
  *
  */
trait ControlMessage

/**
  *
  * @param cancel
  * @param count
  */
case class Subscribe(cancel: Boolean = false, count: Long = Long.MaxValue) extends ControlMessage

/**
  *
  * @param size
  * @param port
  */
case class OnSubscribe(size: Long, port: Int = 5557) extends ControlMessage

/**
  *
  * @param count
  */
case class Next(count: Long = 1000) extends ControlMessage

/**
  *
  * @param count
  */
case class OnNext(count: Long = 1000) extends ControlMessage

/**
  *
  * @param message
  */
case class Error(message: String) extends ControlMessage

/**
  *
  * @param recoverable
  * @param message
  */
case class OnError(recoverable: Boolean = false, message: String = "Error")
    extends ControlMessage


/**
  *
  * @param complete
  */
case class OnComplete(complete: Boolean = true) extends ControlMessage

/**
  *
  * @param complete
  */
case class Complete(complete: Boolean = true) extends ControlMessage

/**
  *
  */
case object Tick

/**
  *
  * @param count
  */
case class BackPressure(count: Long = 1)

/**
  *
  * @param retried
  */
case class NetworkFailure(retried: Int) extends ControlMessage

/**
  *
  */
trait Data

/**
  *
  */
case object Uninitialized extends Data

/**
  *
  * @param value
  */
case class Quit(value: Boolean = true)

/**
  *
  */
case object SendMessages

/**
  *
  */
case object ReceiveMessages

/**
  *
  * @param error
  */
case class ZMQRecoverableError(error: OnError) extends Throwable

/**
  *
  * @param error
  */
case class ZMQIrrecoverableError(error: OnError) extends Throwable

/**
  *
  */
case object TimeOut

/**
  *
  * @param msg
  */
case class RequestMsg(msg: Message)

/**
  *
  * @param msg
  */
case class ReplyMsg(msg: ControlMessage)

/**
  *
  */
class NetworkFailureException extends Exception

/**
  *
  */
class PollInterruptedException extends Exception

/**
  *
  * @param msg
  * @param time
  */
case class NetworkFailureNotification(msg: String, time: Long)


/**
  * based on the MDialog Message for zeroMQ
  */
object Message {
  def apply(parts: ByteString*) = new Message(parts: _*)
  def unapplySeq(message: Message) = IndexedSeq.unapplySeq(message)
}

/**
  *
  * @param parts
  */
class Message(parts: ByteString*)
  extends IndexedSeq[ByteString]
    //with IndexedSeqLike[ByteString, Message]
  {
  private val underlying: Seq[ByteString] = parts.toIndexedSeq

  /**
    *
    * @param idx
    * @return
    */
  override def apply(idx: Int): ByteString = underlying(idx)


  /**
    *
     * @return
    */
  override def length = underlying.length

  /*/**
    *
    * @return
    */
   def newBuilder: mutable.Builder[ByteString, Message] =
    new mutable.GrowableBuilder[ByteString,Message](this)
    //ArrayBuffer.empty[ByteString].mapResult(Message.apply)*/
}
