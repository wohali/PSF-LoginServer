// Copyright (c) 2017 PSForever
package net.psforever.objects.serverobject.doors

import net.psforever.objects.serverobject.PlanetSideServerObject
import net.psforever.objects.Player
import net.psforever.packet.game.UseItemMessage

/**
  * A structure-owned server object that is a "door" that can open and can close.
  * @param ddef the `ObjectDefinition` that constructs this object and maintains some of its immutable fields
  */
class Door(private val ddef : DoorDefinition) extends PlanetSideServerObject {
  private var openState : Boolean = false

  def Open : Boolean = openState

  def Open_=(open : Boolean) : Boolean = {
    openState = open
    Open
  }

  def Use(player : Player, msg : UseItemMessage) : Door.Exchange = {
    if(!openState) {
      openState = true
      Door.OpenEvent()
    }
    else {
      openState = false
      Door.CloseEvent()
    }
  }

  def Definition : DoorDefinition = ddef
}

object Door {
  /**
    * Entry message into this `Door` that carries the request.
    * @param player the player who sent this request message
    * @param msg the original packet carrying the request
    */
  final case class Use(player : Player, msg : UseItemMessage)

  /**
    * A basic `Trait` connecting all of the actionable `Door` response messages.
    */
  sealed trait Exchange

  /**
    * Message that carries the result of the processed request message back to the original user (`player`).
    * @param player the player who sent this request message
    * @param msg the original packet carrying the request
    * @param response the result of the processed request
    */
  final case class DoorMessage(player : Player, msg : UseItemMessage, response : Exchange)

  /**
    * This door will open.
    */
  final case class OpenEvent() extends Exchange

  /**
    * This door will close.
    */
  final case class CloseEvent() extends Exchange

  /**
    * This door will do nothing.
    */
  final case class NoEvent() extends Exchange

  /**
    * Overloaded constructor.
    * @param tdef the `ObjectDefinition` that constructs this object and maintains some of its immutable fields
    */
  def apply(tdef : DoorDefinition) : Door = {
    new Door(tdef)
  }

  import akka.actor.ActorContext
  /**
    * Instantiate an configure a `Door` object
    * @param id the unique id that will be assigned to this entity
    * @param context a context to allow the object to properly set up `ActorSystem` functionality
    * @return the `Door` object
    */
  def Constructor(id : Int, context : ActorContext) : Door = {
    import akka.actor.Props
    import net.psforever.objects.GlobalDefinitions

    val obj = Door(GlobalDefinitions.door)
    obj.Actor = context.actorOf(Props(classOf[DoorControl], obj), s"${GlobalDefinitions.door.Name}_$id")
    obj
  }
}
