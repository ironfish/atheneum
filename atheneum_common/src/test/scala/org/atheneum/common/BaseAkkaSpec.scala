package org.atheneum.common

import akka.actor.{ ActorIdentity, ActorRef, ActorSystem, Identify }
import akka.testkit.{ EventFilter, TestEvent, TestProbe }
import org.scalatest.BeforeAndAfterAll
import scala.concurrent.duration.{ DurationInt, FiniteDuration }

abstract class BaseAkkaSpec extends BaseSpec with BeforeAndAfterAll {

  implicit class TestProbeOps(probe: TestProbe) {

    def expectActor(path: String, max: FiniteDuration = probe.remaining): ActorRef = {
      probe.within(max) {
        var actor = null: ActorRef
        probe.awaitAssert {
          (probe.system actorSelection path).tell(Identify(path), probe.ref)
          probe.expectMsgPF(100 milliseconds) {
            case ActorIdentity(`path`, Some(ref)) => actor = ref
          }
        }
        actor
      }
    }
  }

  implicit val system = ActorSystem()
  system.eventStream.publish(TestEvent.Mute(EventFilter.debug()))
  system.eventStream.publish(TestEvent.Mute(EventFilter.info()))
  system.eventStream.publish(TestEvent.Mute(EventFilter.warning()))
  system.eventStream.publish(TestEvent.Mute(EventFilter.error()))

  override protected def afterAll(): Unit = {
    system.shutdown()
    system.awaitTermination()
  }
}
