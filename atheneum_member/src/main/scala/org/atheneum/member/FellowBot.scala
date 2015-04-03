// Copyright (C) 2015 Duncan DeVore @ironfish
package org.atheneum.member

import java.util.UUID

import akka.actor.{ Actor, ActorLogging, Props }
import akka.cluster.Cluster
import akka.contrib.pattern.ClusterSharding

import org.atheneum.common.Protocol.ErrorMsg

import scala.concurrent.duration._

object FellowBot {
  private case object Tick

  def props: Props =
    Props(new FellowBot)
}

class FellowBot extends Actor with ActorLogging {

  import FellowBot.Tick
  import context.dispatcher

  val Age18: Int = 18
  val Age28: Int = 28
  val Age10: Int = 10
  val LogGet = "\r\n\r\n[****BOT-GET] [{}] from [{}]\r\n"
  val LogRegister = "\r\n\r\n[****BOT-REGISTER-CMD] [{}] from [{}]\r\n"
  val LogChangeName = "\r\n\r\n[****BOT-CHANGE-NAME-CMD] [{}] from [{}]\r\n"
  val LogChangeAge = "\r\n\r\n[****BOT-CHANGE-AGE-CMD] [{}] from [{}]\r\n"
  val LogErr = "\r\n\r\n[****BOT-ERROR] [{}] from [{}]\r\n"
  val tickTask = context.system.scheduler.schedule(3.seconds, 3.seconds, self, Tick)
  val fellowRegion = ClusterSharding(context.system).shardRegion(Fellow.shardName)
  val from = Cluster(context.system).selfAddress.hostPort

  override def postStop(): Unit = {
    super.postStop()
    tickTask.cancel()
  }

  def receive: Receive = registerFellow()

  def registerFellow(cnt: Int = 0): Receive = {
    case Tick =>
      val id = UUID.randomUUID().toString
      val expVer: Long = -1L
      val cmd = Fellow.Register(id, name = s"Fellow[$cnt] Clark Kent", age = Age18, address = "123 Anywhere, USA")
      fellowRegion ! cmd
      log.info(LogRegister, cmd)
      context.become(getFellow(id, expVer + 1, changeFellowName(cnt, id, expVer + 1)))
  }

  def changeFellowName(cnt: Int, id: String, expVer: Long): Receive = {
    case Tick =>
      val cmd = Fellow.ChangeName(id, expVer, s"Fellow[$cnt] Bruce Wayne")
      fellowRegion ! cmd
      log.info(LogChangeName, cmd)
      context.become(getFellow(id, expVer + 1, changeFellowAge(cnt, id, expVer + 1)))
  }

  def changeFellowAge(cnt: Int, id: String, expVer: Long): Receive = {
    case Tick =>
      val cmd = Fellow.ChangeAge(id, expVer, Age28)
      fellowRegion ! cmd
      log.info(LogChangeAge, cmd)
      context.become(getFellow(id, expVer + 1, changeAgeError(cnt, id, expVer + 1)))
  }

  def changeAgeError(cnt: Int, id: String, expVer: Long): Receive = {
    case Tick =>
      val cmd = Fellow.ChangeAge(id, expVer, Age10)
      fellowRegion ! cmd
      log.info(LogChangeAge, cmd)
      context.become(getFellow(id, expVer + 1, registerFellow(cnt + 1)))
  }

  def getFellow(id: String, expVer: Long, become: Receive): Receive = {
    case Tick =>
      fellowRegion ! Fellow.GetFellow(id, expVer)
    case msg: Fellow.FellowMbr =>
      log.info(LogGet, msg, from)
      context.become(become)
    case err: ErrorMsg =>
      log.error(LogErr, err, from)
      context.become(become)
  }
}
