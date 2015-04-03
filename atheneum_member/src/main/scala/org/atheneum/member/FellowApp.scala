// Copyright (C) 2015 Duncan DeVore @ironfish
package org.atheneum.member

import akka.actor.{ ActorIdentity, ActorPath, ActorSystem, Identify, Props }
import akka.contrib.pattern.ClusterSharding
import akka.pattern.ask
import akka.persistence.journal.leveldb.{ SharedLeveldbJournal, SharedLeveldbStore }
import akka.util.Timeout

import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._

object FellowApp {

  val P2551 = "2551"

  def main (args: Array[String]): Unit = {
    if (args.isEmpty) {
      startup(Seq(P2551, "2552", "0"))
    } else {
      startup(args)
    }
  }
  def startup(ports: Seq[String]): Unit = {

    ports foreach { port =>
      // Override the configuration of the port
      val config = ConfigFactory.parseString("akka.remote.netty.tcp.port=" + port).withFallback(ConfigFactory.load())

      // Create an Akka system
      val system = ActorSystem("ClusterSystem", config)

      startupSharedJournal(system, startStore = port == P2551, path =
        ActorPath.fromString(s"akka.tcp://ClusterSystem@127.0.0.1:$P2551/user/store"))

      // Create Sharded region for asset
      ClusterSharding(system).start(
        typeName = Fellow.shardName,
        entryProps = Some(Fellow.props),
        idExtractor = Fellow.idExtractor,
        shardResolver = Fellow.shardResolver)

      // This if statement is for demonstration purposes only
      // In the normal bootstrap we will not be starting up a bot.
      if (port != "2551" && port != "2552") {
        system.actorOf(FellowBot.props, "FellowBot")
      }
    }

    def startupSharedJournal(system: ActorSystem, startStore: Boolean, path: ActorPath): Unit = {
      // Start the shared journal one one node (don't crash this SPOF)
      // This will not be needed with a distributed journal
      if (startStore) {
        system.actorOf(Props[SharedLeveldbStore], "store")
      }
      // register the shared journal
      import system.dispatcher
      implicit val timeout = Timeout(15.seconds)
      val f = system.actorSelection(path) ? Identify(None)
      f.onSuccess {
        case ActorIdentity(_, Some(ref)) => SharedLeveldbJournal.setStore(ref, system)
        case _ =>
          system.log.error("Shared journal not started at {}", path)
          system.shutdown()
      }
      f.onFailure {
        case _ =>
          system.log.error("Lookup of shared journal at {} timed out", path)
          system.shutdown()
      }
    }
  }
}
