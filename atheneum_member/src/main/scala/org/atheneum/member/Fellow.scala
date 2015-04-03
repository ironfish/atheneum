// Copyright (C) 2015 Duncan DeVore @ironfish
package org.atheneum.member

import akka.actor.{ ActorLogging, Props, ReceiveTimeout }
import akka.contrib.pattern.ShardRegion
import akka.contrib.pattern.ShardRegion.{ IdExtractor, Passivate }
import akka.persistence.PersistentActor

import org.atheneum.common.Protocol.{ Cmd, Msg, ErrorMsg, Evt }

import scala.concurrent.duration._
import scala.util.{ Either, Left, Right }

object Fellow {

  val MinimumAge: Int = 18

  private def requireId[C <: Cmd](cmd: C): Either[ErrorMsg, C] =
    if (!cmd.id.isEmpty) Right(cmd) else Left(ErrorMsg(List(s"$cmd id required.")))

  private def requireVersion[C <: Cmd](fellow: FellowMbr, cmd: C): Either[ErrorMsg, C] =
    if (cmd.expVer == fellow.ver) Right(cmd) else Left(ErrorMsg(List(s"$cmd version does not match $fellow version.")))

  private def requireName[C <: RequiredName](cmd: C): Either[ErrorMsg, C] =
    if (!cmd.name.isEmpty) Right(cmd) else Left(ErrorMsg(List(s"Fellow $cmd.id must have a name.")))

  private def requireAge[C <: RequiredAge](cmd: C): Either[ErrorMsg, C] =
    if (cmd.age >= 18) {
      Right(cmd)
    } else {
      Left(ErrorMsg(List(s"Fellow $cmd.id supplied age of $cmd.age does not meet the minimum required age of $MinimumAge.")))
    }

  private def validateAddress(cmd: ChangeAddress): Either[ErrorMsg, ChangeAddress] =
    if (!cmd.address.isEmpty) Right(cmd) else Left(ErrorMsg(List(s"Fellow $cmd.id may not have an empty address.")))

  def registerFellow(cmd: Register): Either[ErrorMsg, Registered] = for {
    a <- requireId(cmd).right
    b <- requireName(a).right
    c <- requireAge(b).right
  } yield Registered(c.id, 0L, c.name, c.age, cmd.address)

  def changeFellowName(fellow: FellowMbr, cmd: ChangeName): Either[ErrorMsg, NameChanged] = for {
    a <- requireId(cmd).right
    b <- requireVersion(fellow, a).right
    c <- requireName(b).right
  } yield NameChanged(c.id, c.expVer + 1, c.name)

  def changeFellowAge(fellow: FellowMbr, cmd: ChangeAge): Either[ErrorMsg, AgeChanged] = for {
    a <- requireId(cmd).right
    b <- requireVersion(fellow, a).right
    c <- requireAge(b).right
  } yield AgeChanged(c.id, c.expVer + 1, c.age)

  def changeFellowAddress(fellow: FellowMbr, cmd: ChangeAddress): Either[ErrorMsg, AddressChanged] = for {
    a <- requireId(cmd).right
    b <- requireVersion(fellow, a).right
  } yield AddressChanged(b.id, b.expVer + 1, b.address)

  case object Stop

  sealed trait RequiredName extends Cmd {
    def name: String
  }

  sealed trait RequiredAge extends Cmd {
    def age: Int
  }

  sealed trait Required extends RequiredName with RequiredAge

  final case class GetFellow(id: String, expVer: Long) extends Cmd
  final case class Register(id: String, expVer: Long = -1L, name: String, age: Int, address: String) extends Required
  final case class ChangeName(id: String, expVer: Long, name: String) extends RequiredName
  final case class ChangeAge(id: String, expVer: Long, age: Int) extends RequiredAge
  final case class ChangeAddress(id: String, expVer: Long, address: String) extends Cmd

  final case class Registered(id: String, ver: Long, name: String, age: Int, address: String) extends Evt
  final case class NameChanged(id: String, ver: Long, name: String) extends Evt
  final case class AgeChanged(id: String, ver: Long, age: Int) extends Evt
  final case class AddressChanged(id: String, ver: Long, address: String) extends Evt

  final case class FellowMbr(id: String = "", ver: Long = -1L, name: String ="", age: Int = 0, address: String = "")

  /**
    * The start state for asset at boot.
    */
  private def empty: FellowMbr = FellowMbr()

  /**
    * Local in-memory state object for fellow.
    *
    * This class manages the local state which is mutable but thread-safe because it is not shared.
    *
    * @constructor Creates a new local state holder for the fellow `PersistentActor`.
    * @param fellow a [[Fellow]].
    */
  private case class State(fellow: FellowMbr) {
    def updated(e: Evt): State = e match {
      case Registered(i,v,n,a,ad) => copy(fellow.copy(i, v, n, a, ad))
      case NameChanged(i,v,n)     => copy(fellow.copy(ver = v, name = n))
      case AgeChanged(i,v,a)      => copy(fellow.copy(ver = v, age = a))
      case AddressChanged(i,v,a)  => copy(fellow.copy(ver = v, address = a))
    }
  }

  /**
    * Interface of the partial function used by the [[ShardRegion]] to
    * extract the entry id and the message to send to the entry from an
    * incoming message.
    */
  val idExtractor: ShardRegion.IdExtractor = {
    case c: Cmd => (c.id, c)
  }

  /**
    * Interface of the function used by the [[ShardRegion]] to
    * extract the shard id from an incoming message.
    * Only messages that passed the [[IdExtractor]] will be used
    * as input to this function.
    */
  val shardResolver: ShardRegion.ShardResolver = {
    case c: Cmd => (math.abs(c.id.hashCode) % 100).toString
  }

  /**
    * Shard name to manage [[Fellow]] aggregates.
    */
  val shardName: String = "Fellow"

   def props: Props =
     Props(new Fellow)
}

class Fellow extends PersistentActor with ActorLogging {

  import Fellow._

  // self.path.parent.name is the type name
  // self.path.name is the entry identifier
  override def persistenceId: String = self.path.parent.name + "-" + self.path.name

  // passivate the aggregate when no activity
  context.setReceiveTimeout(2.minutes)

  /** Local mutable threadsafe state variable for maintaining a current state representation of asset. */
  private var state = State(empty)

  /** Recover events from journal on boot */
  def receiveRecover: Receive = {
    case e: Evt => e match {
      case r: Registered =>
        context.become(registered)
        state = state.updated(r)
      case nc: NameChanged =>
        state = state.updated(nc)
      case ac: AgeChanged =>
        state = state.updated(ac)
      case ac: AddressChanged =>
        state = state.updated(ac)
    }
  }

  /** The first thing this actor must process is the registration of a fellow. */
  override def receiveCommand: Receive = register

 val LogRegistered = "\r\n\r\n[****FELLO-CREATED] {}.\r\n"
 val LogNameChanged = "\r\n\r\n[****FELLOW-NAME-CHANGED] {}.\r\n"
 val LogAgeChanged = "\r\n\r\n[****FELLOW-AGE-CHANGED] {}.\r\n"
 val LogAddressChanged = "\r\n\r\n[****FELLOW-ADDRESS-CHANGED] {}.\r\n"

  /** Receive loop for initial registration of a fellow. */
  def register: Receive = {
    case cmd: Register => registerFellow(cmd) fold(
      f => sender ! f,
      s => persist(s) { e =>
        state = state.updated(e)
        context.become(registered)
//         log.info(LogRegistered, e)
      })
  }

  /** Receive loop for all commands after a fellow has been registered. */
  def registered: Receive = {
    case cmd: GetFellow => requireId(cmd) fold(
      f => sender ! f,
      s => sender ! state.fellow
      )
    case cmd: ChangeName => changeFellowName(state.fellow, cmd) fold (
      f => sender ! f,
      s => persist(s) { e =>
        state = state.updated(e)
//         log.info(LogNameChanged, e)
      })
    case cmd: ChangeAge => changeFellowAge(state.fellow, cmd) fold (
      f => sender ! f,
      s => persist(s) { e =>
        state = state.updated(e)
//         log.info(LogAgeChanged, e)
      })
    case cmd: ChangeAddress => changeFellowAddress(state.fellow, cmd) fold (
      f => sender ! f,
      s => persist(s) { e =>
        state = state.updated(e)
//         log.info(LogAddressChanged, e)
      })
  }

  val LogTimeout = "\r\n\r\n[**** FELLOW-TIMEOUT] {}.\r\n"
  val LogStop = "\r\n\r\n[**** FELLOW-STOP] {}.\r\n"
  val LogUnhandled = "\r\n\r\n[****FELLOW-UNHANDLED] {}.\r\n"

  override def unhandled(msg: Any): Unit = msg match {
    case ReceiveTimeout =>
      log.info(LogTimeout, msg)
      context.parent ! Passivate(stopMessage = Stop)
    case Stop =>
      log.info(LogStop, msg)
      context.stop(self)
    case _ =>
      log.info(LogUnhandled, msg)
      super.unhandled(msg)
  }
}
