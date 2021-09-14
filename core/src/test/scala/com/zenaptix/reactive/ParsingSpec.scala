package com.zenaptix.reactive

import com.typesafe.scalalogging.LazyLogging
import org.scalatest.{Matchers, FlatSpec}
import org.json4s._
import org.json4s.native.JsonMethods._
import org.json4s.native.Serialization
import org.json4s.native.Serialization.{read, write}

/**
  * Created by ian on 2015/03/17.
  *
  */
class ParsingSpec extends FlatSpec with Matchers with LazyLogging {

  implicit val formats = Serialization.formats(
      ShortTypeHints(
          List(classOf[Subscribe],
               classOf[OnSubscribe],
               classOf[Next],
               classOf[OnNext],
               classOf[OnComplete],
               classOf[Complete],
               classOf[OnError])))

  println()
  val json1 =
    """{"jsonClass":"OnSubscribe", "size": 9223372036854775807,"port": 5557}"""
  val obj1 = parse(json1).extract[OnSubscribe]
  println(obj1)
  val ser1 = write(obj1)
  println(ser1)
  val obja = read[OnSubscribe](ser1)
  println(obja)

  println()
  val json2 = """{"jsonClass":"Subscribe","cancel":false}"""
  val obj2 = parse(json2).extract[Subscribe]
  println(obj2)
  val ser2 = write(obj2)
  println(ser2)
  val objb = read[Subscribe](ser2)
  println(objb)

  println()
  val json3 = """{"jsonClass":"Next","count":1000}"""
  val obj3 = parse(json3).extract[Next]
  println(obj3)
  val ser3 = write(obj3)
  println(ser3)
  val objc = read[Next](ser3)
  println(objc)

  println()
  val json4 = """{"jsonClass":"OnNext","count":1000}"""
  val obj4 = parse(json4).extract[OnNext]
  println(obj4)
  val ser4 = write(obj4)
  println(ser4)
  val objd = read[OnNext](ser4)
  println(objd)

  println()
  val json5 = """{"jsonClass":"OnComplete","complete":true}"""
  val obj5 = parse(json5).extract[OnComplete]
  println(obj5)
  val ser5 = write(obj5)
  println(ser5)
  val obje = read[OnComplete](ser5)
  println(obje)

  println()
  val json10 = """{"jsonClass":"Complete","complete":true}"""
  val obj10 = parse(json10).extract[Complete]
  println(obj10)
  val ser10 = write(obj10)
  println(ser10)
  val objbz = read[Complete](ser10)
  println(obj10)

  println()
  val json6 =
    """{"jsonClass":"OnError","recoverable":false,"message":"stuffed"}"""
  val obj6 = parse(json6).extract[OnError]
  println(obj6)
  val ser6 = write(obj6)
  println(ser6)
  val objf = read[ControlMessage](ser6)
  val str = "OnError"
  println(objf)

  "A RouteBuilder " should " successfully execute an HTTP request to a external service" in {
    val jsonList = List(json1, json2, json3, json4, json5, json6)
    val classes = for {
      s <- jsonList
      t <- List(parse(s))
      v <- List(t.extract[ControlMessage])
      JObject(u) <- t
      JField("jsonClass", JString(clazz)) <- u
    } yield (Class.forName("com.zenaptix.reactive." + clazz), v)

    println(classes)

  }

}
