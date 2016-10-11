import sbt._

class Plugins(info: ProjectInfo) extends PluginDefinition(info) {
  val akkaRepo   = "Akka Repo" at "http://repo.akka.io/releases"
  val akkaPlugin = "se.scalablesolutions.akka" % "akka-sbt-plugin" % "1.3.1"
}