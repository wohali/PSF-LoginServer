// Copyright (c) 2017 PSForever
package objects

import akka.actor.{Actor, ActorRef, Props}
import net.psforever.objects.Player
import net.psforever.objects.definition.{ObjectDefinition, SeatDefinition}
import net.psforever.objects.mount.{Mountable, MountableBehavior}
import net.psforever.objects.serverobject.PlanetSideServerObject
import net.psforever.objects.vehicles.Seat
import net.psforever.types.{CharacterGender, PlanetSideEmpire}

import scala.concurrent.duration.Duration

class MountableControl1Test extends ActorTest() {
  "MountableControl" should {
    "construct" in {
      val obj = new MountableTest.MountableTestObject
      obj.Actor = system.actorOf(Props(classOf[MountableTest.MountableTestControl], obj), "mech")
      assert(obj.Actor != ActorRef.noSender)
    }
  }
}

class MountableControl2Test extends ActorTest() {
  "MountableControl" should {
    "let a player mount" in {
      val player = Player("test", PlanetSideEmpire.TR, CharacterGender.Male, 0, 0)
      val obj = new MountableTest.MountableTestObject
      obj.Actor = system.actorOf(Props(classOf[MountableTest.MountableTestControl], obj), "mountable")
      val msg = Mountable.TryMount(player, 0)

      obj.Actor ! msg
      val reply = receiveOne(Duration.create(100, "ms"))
      assert(reply.isInstanceOf[Mountable.MountMessages])
      val reply2 = reply.asInstanceOf[Mountable.MountMessages]
      assert(reply2.player == player)
      assert(reply2.response.isInstanceOf[Mountable.CanMount])
      val reply3 = reply2.response.asInstanceOf[Mountable.CanMount]
      assert(reply3.obj == obj)
      assert(reply3.seat_num == 0)
    }
  }
}

class MountableControl3Test extends ActorTest() {
  "MountableControl" should {
    "block a player from mounting" in {
      val player1 = Player("test1", PlanetSideEmpire.TR, CharacterGender.Male, 0, 0)
      val player2 = Player("test2", PlanetSideEmpire.TR, CharacterGender.Male, 0, 0)
      val obj = new MountableTest.MountableTestObject
      obj.Actor = system.actorOf(Props(classOf[MountableTest.MountableTestControl], obj), "mountable")
      obj.Actor ! Mountable.TryMount(player1, 0)
      receiveOne(Duration.create(100, "ms")) //consume reply

      obj.Actor ! Mountable.TryMount(player2, 0)
      val reply = receiveOne(Duration.create(100, "ms"))
      assert(reply.isInstanceOf[Mountable.MountMessages])
      val reply2 = reply.asInstanceOf[Mountable.MountMessages]
      assert(reply2.player == player2)
      assert(reply2.response.isInstanceOf[Mountable.CanNotMount])
      val reply3 = reply2.response.asInstanceOf[Mountable.CanNotMount]
      assert(reply3.obj == obj)
      assert(reply3.seat_num == 0)
    }
  }
}

object MountableTest {
  class MountableTestObject extends PlanetSideServerObject with Mountable {
    private val seats : Map[Int, Seat] = Map( 0 -> new Seat(new SeatDefinition()) )
    def Seats : Map[Int, Seat] = seats
    def Seat(seatNum : Int) : Option[Seat] = seats.get(seatNum)
    def MountPoints : Map[Int, Int] = Map(1 -> 0)
    def GetSeatFromMountPoint(mount : Int) : Option[Int] = MountPoints.get(mount)
    def PassengerInSeat(user : Player) : Option[Int] = {
      if(seats(0).Occupant.contains(user)) {
        Some(0)
      }
      else {
        None
      }
    }
    def Definition : ObjectDefinition = null //eh whatever
  }

  class MountableTestControl(obj : Mountable) extends Actor with MountableBehavior {
    override def MountableObject = obj

    def receive : Receive = mountableBehavior
  }
}
