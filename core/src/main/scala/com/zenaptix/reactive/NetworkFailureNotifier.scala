package com.zenaptix.reactive

import akka.actor.ActorRef
import akka.event.{SubchannelClassification, EventBus}
import akka.util.Subclassification

/**
  * Created by ian on 2015/06/28.
  *
  */
object NetworkFailureNotifier extends EventBus with SubchannelClassification {
  type Event = (String, Any, ActorRef)
  type Classifier = String
  type Subscriber = ActorRef

  /**
    *
    * @param event
    * @return
    */
  override protected def classify(event: Event): Classifier = event._1

  /**
    *
    * @return
    */
  protected def subclassification = new Subclassification[Classifier] {
    def isEqual(x: Classifier, y: Classifier) = x == y
    def isSubclass(x: Classifier, y: Classifier) = x.startsWith(y)
  }

  /**
    *
    * @param event
    * @param subscriber
    */
  override protected def publish(event: Event, subscriber: Subscriber): Unit =
    subscriber.tell(event._2, event._3)
}


