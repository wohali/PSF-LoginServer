// Copyright (c) 2016 PSForever.net to present
import java.net.{InetAddress, InetSocketAddress}

import akka.actor.{ActorRef, Identify, Actor}
import psforever.crypto.CryptoInterface.{CryptoStateWithMAC, CryptoState}
import psforever.crypto.CryptoInterface
import psforever.net._
import scodec.Attempt.{Successful, Failure}
import scodec.bits._
import scodec.{Err, Attempt, Codec}
import scodec.codecs.{uint16L, uint8L, bytes}
import java.security.SecureRandom

/**
  * Actor that stores crypto state for a connection and filters away any packet metadata.
  */
class CryptoSessionActor extends Actor {
  private[this] val logger = org.log4s.getLogger

  var leftRef : ActorRef = ActorRef.noSender
  var rightRef : ActorRef = ActorRef.noSender

  var cryptoDHState = new CryptoInterface.CryptoDHState()
  var cryptoState : Option[CryptoInterface.CryptoStateWithMAC] = None
  val random = new SecureRandom()

  // crypto handshake state
  var serverChallenge = ByteVector.empty
  var serverChallengeResult = ByteVector.empty
  var serverMACBuffer = ByteVector.empty

  var clientPublicKey = ByteVector.empty
  var clientChallenge = ByteVector.empty
  var clientChallengeResult = ByteVector.empty

  def receive = Initializing

  def Initializing : Receive = {
    case HelloFriend(right) =>
      leftRef = sender()
      rightRef = right.asInstanceOf[ActorRef]

      // who ever we send to has to send something back to us
      rightRef ! HelloFriend(self)

      context.become(NewClient)
    case _ =>
      logger.error("Unknown message")
      context.stop(self)
  }

  def NewClient : Receive = {
    case RawPacket(msg) =>
      // PacketCoding.DecodePacket
      PacketCoding.UnmarshalPacket(msg) match {
        case Failure(e) => logger.error("Could not decode packet: " + e)
        case Successful(p) =>
          //println("RECV: " + p)

          p match {
            case ControlPacket(_, ClientStart(nonce)) =>
              sendResponse(PacketCoding.CreateControlPacket(ServerStart(nonce, Math.abs(random.nextInt()))))

              context.become(CryptoExchange)
            case default =>
              logger.error("Unexpected packet type " + p)
          }
      }
    case default => logger.error(s"Invalid message received ${default}")
  }

  def CryptoExchange : Receive = {
    case RawPacket(msg) =>
      PacketCoding.UnmarshalPacket(msg, CryptoPacketOpcode.ClientChallengeXchg) match {
        case Failure(e) => logger.error("Could not decode packet: " + e)
        case Successful(p) =>
          //println("RECV: " + p)

          p match {
            case CryptoPacket(seq, ClientChallengeXchg(time, challenge, p, g)) =>
              // initialize our crypto state from the client's P and G
              cryptoDHState.start(p, g)

              // save the client challenge
              clientChallenge = ServerChallengeXchg.getCompleteChallenge(time, challenge)

              // save the packet we got for a MAC check later. drop the first 3 bytes
              serverMACBuffer ++= msg.drop(3)

              val serverTime = System.currentTimeMillis() / 1000L
              val randomChallenge = getRandBytes(0xc)

              // store the complete server challenge for later
              serverChallenge = ServerChallengeXchg.getCompleteChallenge(serverTime, randomChallenge)

              val packet = PacketCoding.CreateCryptoPacket(seq,
                ServerChallengeXchg(serverTime, randomChallenge, cryptoDHState.getPublicKey))

              val sentPacket = sendResponse(packet)

              // save the sent packet a MAC check
              serverMACBuffer ++= sentPacket.drop(3)

              context.become(CryptoSetupFinishing)
            case default => logger.error("Unexpected packet type " + p)
          }
      }
    case default => logger.error(s"Invalid message received ${default}")
  }

  def CryptoSetupFinishing : Receive = {
    case RawPacket(msg) =>
      PacketCoding.UnmarshalPacket(msg, CryptoPacketOpcode.ClientFinished) match {
        case Failure(e) => logger.error("Could not decode packet: " + e)
        case Successful(p) =>
          //println("RECV: " + p)

          p match {
            case CryptoPacket(seq, ClientFinished(clientPubKey, clientChalResult)) =>
              clientPublicKey = clientPubKey
              clientChallengeResult = clientChalResult

              // save the packet we got for a MAC check later
              serverMACBuffer ++= msg.drop(3)

              val agreedValue = cryptoDHState.agree(clientPublicKey)

              /*println("Agreed: " + agreedValue)
              println(s"Client challenge: $clientChallenge")*/
              val agreedMessage = ByteVector("master secret".getBytes) ++ clientChallenge ++
                hex"00000000" ++ serverChallenge ++ hex"00000000"

              //println("In message: " + agreedMessage)

              val masterSecret = CryptoInterface.MD5MAC(agreedValue,
                agreedMessage,
                20)

              //println("Master secret: " + masterSecret)

              serverChallengeResult = CryptoInterface.MD5MAC(masterSecret,
                ByteVector("server finished".getBytes) ++ serverMACBuffer ++ hex"01",
                0xc)

              val clientChallengeResultCheck = CryptoInterface.MD5MAC(masterSecret,
                ByteVector("client finished".getBytes) ++ serverMACBuffer ++ hex"01" ++ clientChallengeResult ++ hex"01",
                0xc)

              //println("Check result: " + CryptoInterface.verifyMAC(clientChallenge, clientChallengeResult))

              val decExpansion = ByteVector("client expansion".getBytes) ++ hex"0000" ++ serverChallenge ++
                hex"00000000" ++ clientChallenge ++ hex"00000000"

              val encExpansion = ByteVector("server expansion".getBytes) ++ hex"0000" ++ serverChallenge ++
                hex"00000000" ++ clientChallenge ++ hex"00000000"

              /*println("DecExpansion: " + decExpansion)
              println("EncExpansion: " + encExpansion)*/

              // expand the encryption and decryption keys
              // The first 20 bytes are for RC5, and the next 16 are for the MAC'ing keys
              val expandedDecKey = CryptoInterface.MD5MAC(masterSecret,
                decExpansion,
                0x40) // this is what is visible in IDA

              val expandedEncKey = CryptoInterface.MD5MAC(masterSecret,
                encExpansion,
                0x40)

              val decKey = expandedDecKey.take(20)
              val encKey = expandedEncKey.take(20)
              val decMACKey = expandedDecKey.drop(20).take(16)
              val encMACKey = expandedEncKey.drop(20).take(16)

              /*println("**** DecKey: " + decKey)
              println("**** EncKey: " + encKey)
              println("**** DecMacKey: " + decMACKey)
              println("**** EncMacKey: " + encMACKey)*/

              // spin up our encryption program
              cryptoState = Some(new CryptoStateWithMAC(decKey, encKey, decMACKey, encMACKey))

              val packet = PacketCoding.CreateCryptoPacket(seq,
                ServerFinished(serverChallengeResult))

              sendResponse(packet)

              context.become(Established)
            case default => failWithError("Unexpected packet type " + default)
          }
      }
    case default => failWithError(s"Invalid message received ${default}")
  }

  def Established : Receive = {
    case RawPacket(msg) =>
      PacketCoding.UnmarshalPacket(msg) match {
        case Successful(p) =>
          p match {
            case encPacket @ EncryptedPacket(seq, _) =>
              //println("Decrypting packet..." + encPacket)
              PacketCoding.decryptPacket(cryptoState.get, encPacket) match {
                case Successful(packet) =>
                  //println("RECV[E]: " + packet)

                  self ! packet
                case Failure(e) =>
                  logger.error("Failed to decode encrypted packet: " + e)
              }
            case default => failWithError("Unexpected packet type " + default)

          }
        case Failure(e) => logger.error("Could not decode raw packet: " + e)
      }
    case ctrl @ ControlPacket(_, _) =>
      val from = sender()

      handleEstablishedPacket(from, ctrl)
    case game @ GamePacket(_, _, _) =>
      val from = sender()

      handleEstablishedPacket(from, game)
    case default => failWithError(s"Invalid message received ${default}")
  }

  def failWithError(error : String) = {
    logger.error(error)
  }

  def resetState() : Unit = {
    context.become(receive)

    // reset the crypto primitives
    cryptoDHState.close
    cryptoDHState = new CryptoInterface.CryptoDHState()

    if(cryptoState.isDefined) {
      cryptoState.get.close
      cryptoState = None
    }

    serverChallenge = ByteVector.empty
    serverChallengeResult = ByteVector.empty
    serverMACBuffer = ByteVector.empty
    clientPublicKey = ByteVector.empty
    clientChallenge = ByteVector.empty
    clientChallengeResult = ByteVector.empty
  }

  def handleEstablishedPacket(from : ActorRef, cont : PlanetSidePacketContainer) = {
    // we are processing a packet we decrypted
    if(from == self) {
      rightRef ! cont
    } else if(from == rightRef) { // processing a completed packet from the right. encrypt
      val packet = PacketCoding.encryptPacket(cryptoState.get, cont).require
      sendResponse(packet)
    } else {
      logger.error("Invalid sender")
    }
  }

  def sendResponse(cont : PlanetSidePacketContainer) : ByteVector = {
    //println("CRYPTO SEND: " + cont)
    val pkt = PacketCoding.MarshalPacket(cont).require
    val bytes = pkt.toByteVector

    leftRef ! ResponsePacket(bytes)

    bytes
  }

  def getRandBytes(amount : Int) : ByteVector = {
    val array = Array.ofDim[Byte](amount)
    random.nextBytes(array)
    ByteVector.view(array)
  }
}
