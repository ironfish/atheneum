import scala.Console
import scala.util.matching._

shellPrompt in ThisBuild := { state =>
  val pn = Project.extract(state).get(name)
  Console.RED + s"${pn} > " + Console.RESET
}
