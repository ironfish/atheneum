package org.atheneum.member

import java.io.File

import akka.actor.{ ActorIdentity, Identify, Props }
import akka.cluster.Cluster
import akka.contrib.pattern.ClusterSharding
import akka.persistence.Persistence
import akka.persistence.journal.leveldb.{ SharedLeveldbJournal, SharedLeveldbStore }
import akka.remote.testconductor.RoleName
import akka.remote.testkit.{ MultiNodeSpec, MultiNodeConfig, MultiNodeSpecCallbacks }
import akka.testkit.ImplicitSender

import com.typesafe.config.ConfigFactory

import org.apache.commons.io.FileUtils

import org.scalatest.{ WordSpecLike, Matchers, BeforeAndAfterAll }

import scala.concurrent.duration._

/**
 * Hooks up MultiNodeSpec with ScalaTest
 */
trait STMultiNodeSpec extends MultiNodeSpecCallbacks
    with WordSpecLike
    with Matchers
    with BeforeAndAfterAll {

  override def beforeAll() = multiNodeSpecBeforeAll()

  override def afterAll() = multiNodeSpecAfterAll()
}

object FellowSpec extends MultiNodeConfig {
  val controller = role("controller")
  val node1 = role("node1")
  val node2 = role("node2")

  commonConfig(ConfigFactory.parseString("""
    akka.actor.provider = "akka.cluster.ClusterActorRefProvider"
    akka.persistence.journal.plugin = "akka.persistence.journal.leveldb-shared"
    akka.persistence.journal.leveldb-shared.store {
      native = off
      dir = "target/test-shared-journal"
    }
    akka.persistence.snapshot-store.local.dir = "target/test-snapshots"
                                         """))
}

class FellowSpecMultiJvmNode1 extends FellowSpec
class FellowSpecMultiJvmNode2 extends FellowSpec
class FellowSpecMultiJvmNode3 extends FellowSpec

class FellowSpec extends MultiNodeSpec(FellowSpec) with STMultiNodeSpec with ImplicitSender {

  import FellowSpec._

  def initialParticipants = roles.size

  val storageLocations = List(
    "akka.persistence.journal.leveldb.dir",
    "akka.persistence.journal.leveldb-shared.store.dir",
    "akka.persistence.snapshot-store.local.dir").map(s => new File(system.settings.config.getString(s)))

  override protected def atStartup() {
    runOn(controller) {
      storageLocations.foreach(dir => FileUtils.deleteDirectory(dir))
    }
  }

  override protected def afterTermination() {
    runOn(controller) {
      storageLocations.foreach(dir => FileUtils.deleteDirectory(dir))
    }
  }

  def join(from: RoleName, to: RoleName): Unit = {
    runOn(from) {
      Cluster(system) join node(to).address
      startSharding()
    }
    enterBarrier(from.name + "-joined")
  }

  def startSharding(): Unit = {
    ClusterSharding(system).start(
      typeName = Fellow.shardName,
      entryProps = Some(Props(classOf[Fellow])),
      idExtractor = Fellow.idExtractor,
      shardResolver = Fellow.shardResolver)
  }

  "Sharded fellow app" must {

    "setup shared journal" in {
      // start the Persistence extension
      Persistence(system)
      runOn(controller) {
        system.actorOf(Props[SharedLeveldbStore], "store")
      }
      enterBarrier("persistence-started")

      runOn(node1, node2) {
        system.actorSelection(node(controller) / "user" / "store") ! Identify(None)
        val sharedStore = expectMsgType[ActorIdentity].ref.get
        SharedLeveldbJournal.setStore(sharedStore, system)
      }
      enterBarrier("shared-journal-setup-step-1")
    }

    "join cluster" in within(15.seconds) {
      join(node1, node1)
      join(node2, node1)
      enterBarrier("cluster-setup-step-2")
    }

    val Id = "45902e35-4acb-4dc2-9109-73a9126b604f"
    val Ver1: Long = 0L
    val RegName: String = "john jacob jingleheimer schmidt"
    val Age38: Int = 38
    val Addr: String = "123 Anywhere, USA"
    val cmdRegister = Fellow.Register(Id, name = RegName, age = Age38, address = Addr)
    val ExpVer1: Long = 1L
    val ChngName: String = "John Schmidt"
    val cmdChangeName = Fellow.ChangeName(Id, Ver1, ChngName)

    "create, update and retrieve fellow" in within(15.seconds) {
      runOn(node1) {
        val assetRegion = ClusterSharding(system).shardRegion(Fellow.shardName)
        assetRegion ! cmdRegister
        assetRegion ! cmdChangeName
      }
      runOn(node2) {
        val assetRegion = ClusterSharding(system).shardRegion(Fellow.shardName)
        awaitAssert {
          within(1.second) {
            assetRegion ! Fellow.GetFellow(Id, ExpVer1)
            expectMsg(Fellow.FellowMbr(Id,ExpVer1,ChngName,Age38,Addr))
          }
        }
      }
      enterBarrier("fellow-created-updated-and-retrieved-step-3")
    }
  }
}
