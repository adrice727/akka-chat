import sbt._

class ChatProject(info: ProjectInfo) extends DefaultProject(info) with AkkaProject {
  val akkaRepo = "Akka Repo" at "http://repo.akka.io/releases"
  val akkaSTM = akkaModule("stm")
  val akkaRemote = akkaModule("remote")
}

sealed trait Event
case class Login(user: String) extends Event
case class Logout(user: String) extends Event
case class GetChatLog(from: String) extends Event
case class ChatLog(log: List[String]) extends Event
case class ChatMessage(from: String, message: String) extends Event

class ChatClient(val name: String) {
  val chat = Actor.remote.actorFor("chat:service", "localhost", 2552)
  def login = chat ! Login(name)
  def logout = chat ! Logout(name)
  def post(message: String) = chat ! ChatMessage(name, name + ": " + message)
  def chatLog = (chat ? GetChatLog(name)).as[ChatLog]
    .getOrElse(throw new Exception("Couldn't get the chat log from ChatServer"))
}

class Session(user: String, storage: ActorRef) extends Actor {
  private val loginTime = System.currentTimeMillis
  private var userLog: List[String] = Nil

  EventHandler.info(this, "New session for user [%s] has been created at [%h]".format(user, loginTime))

  def receive = {
    case msg @ ChatMessage(from, message) =>
      userLog ::= message
      storage ! msg

    case msg @ GetChatLog(_) =>
      storage forward msg
  }
}

trait ChatServer extends Actor {
  self.faultHandler = OneForOneStrategy(List(classOf[Exception]),5, 5000)
  val storage: ActorRef

  EventHandler.info(this, "Chat server is starting up . . .")

  // actor message handler
  def receive: Receive = sessionManagement orElse chatManagement

  // abstract methods to be defined elsewhere
  protected def chatManagement: Receive
  protected def sessionManagement: Recieve
  protected def shutdownSessions(): Unit

  override def postStop() = {
    EventHandler.info(this, "Chat server is shutting down . . .")
    shutdownSessions
    self.unlink(storage)
    storage.stop()
  }
}

trait SessionManagement { this: Actor =>

  val storage: ActorRef
  val sessions = new HashMap[String, ActorRef]

  protected def sessionManagement: Receive = {
    case Login(username) =>
      EventHandler.info(this, "User [%s] has logged in".format(username))
      val session = actorOf(new Session(username, storage))
      session.start()
      sessions += (username -> session)

    case Logout(username)=>
      EventHandler.info(this, "User [%s] has logged out".format(username))
      val session = sessions(username)
      session.stop()
      session -= username
  }

  protected def shutdownSessions =
    session.foreach { case (_, session) => session.stop() }
}

trait ChatManagement { this: Actor =>

  val sessions = HashMap[String, ActorRef]

  protected def chatManagement: Receive = {
    case msg @ ChatMessage(from, _) => getSession(from).foreach(_ ! msg)
    case msg @ GetChatLog(from) => getSession(from).foreach(_ forward msg)
  }

  private def getSession(from: String) : Option[ActorRef] = {
    if (sessions.contains(from))
      Some(sessions(from))
    else {
      EventHandler.info(this, "Session expired for [%s]".format(from))
      None
    }
  }
}

/**
 * Abstraction of chat storage holding the chat log.
 */
trait ChatStorage extends Actor

/**
 * Memory-backed chat storage implementation.
 */
class ChatMemoryStorage extends ChatStorage {
  self.lifecycle = Permanent

  private var chatLog = TransactionalVector[Array[Byte]]()
  EventHandler.info(this, "Memory-based chat storage is starting up . . .")

  def receive = {
    case msg @ ChatMessage(from, message) =>
      EventHandler.debug(this, "New chat message [%s]".format(message))
      atomic { chatLog + message.getBytes("UTF-8") }

    case GetChatLog(_) =>
      val messageList = atomic { chatLog.map(bytes => new String(bytes, "UTF-8")).toList }
      self.reply(ChatLog(messageList))
  }

  override def postRestart(reason: Throwable) = chatLog = TransactionalVector()
}

/**
  * Creates and links a MemoryChatStorage.
  */
trait MemoryChatStorageFactory { this: Actor =>
  val storage = this.self.spawnLink[MemoryChatStorage]
}



