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



