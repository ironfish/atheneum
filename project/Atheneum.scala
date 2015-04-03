import sbt._
import Keys._
import com.typesafe.sbt.SbtMultiJvm
import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys._
import com.typesafe.sbteclipse.plugin.EclipsePlugin._
import org.scalastyle.sbt.ScalastylePlugin._

object Atheneum {

  val akkaVer = "2.3.9"
  val commonsIoVer = "2.4"
  val logbackVer = "1.1.2"
  val scalaVer = "2.11.5"
  val scalaParsersVer= "1.0.3"
  val scalaTestVer = "2.2.2"

  private lazy val scalaCompileOptions = Seq(
    "-deprecation",
    "-unchecked",
    "-feature",
    "-language:_",
    "-target:jvm-1.7",
    "-encoding", "UTF-8"
  )

  private lazy val scalaCompileDocOptions = (
    name in (Compile, doc),
    version in (Compile, doc)) map DefaultOptions.scaladoc

  lazy val dependencies = Seq(
    "com.typesafe.akka"        %% "akka-persistence-experimental"    % akkaVer,
    "com.typesafe.akka"        %% "akka-contrib"                     % akkaVer,
    "com.typesafe.akka"        %% "akka-slf4j"                       % akkaVer,
    "ch.qos.logback"            % "logback-classic"                  % logbackVer,
    "org.scala-lang.modules"   %% "scala-parser-combinators"         % scalaParsersVer,
    "com.typesafe.akka"        %% "akka-multi-node-testkit"          % akkaVer,
    "commons-io"                % "commons-io"                       % commonsIoVer       % "test",
    "com.typesafe.akka"        %% "akka-testkit"                     % akkaVer            % "test",
    "org.scalatest"            %% "scalatest"                        % scalaTestVer       % "test"
  )

  private lazy val compileScalastyle = taskKey[Unit]("compileScalastyle")

  val settings: Seq[Def.Setting[_]] =
    Seq(
      organization := "org.atheneum",
      scalaVersion := scalaVer,
      scalacOptions in Compile ++= scalaCompileOptions,
      scalacOptions in (Compile, doc) <++= scalaCompileDocOptions,
      (scalastyleConfig in Compile) := file("project/scalastyle-config.xml"),
      compileScalastyle := org.scalastyle.sbt.ScalastylePlugin.scalastyle.in(Compile).toTask("").value,
      (compile in Compile) <<= (compile in Compile) dependsOn compileScalastyle,
      unmanagedSourceDirectories in Compile := List((scalaSource in Compile).value),
      unmanagedSourceDirectories in Test := List((scalaSource in Test).value),
      EclipseKeys.createSrc := EclipseCreateSrc.Default + EclipseCreateSrc.Resource,
      EclipseKeys.eclipseOutput := Some(".target"),
      EclipseKeys.withSource := true,
      EclipseKeys.skipParents in ThisBuild := true,
      EclipseKeys.skipProject := false,
      parallelExecution in Test := false,
      logBuffered in Test := false,
      parallelExecution in ThisBuild := false)

  lazy val multiJvmSettings: Seq[Def.Setting[_]] =
    SbtMultiJvm.multiJvmSettings ++
    Seq(
      // make sure that MultiJvm tests are compiled by default test compilation
      compile in MultiJvm <<= (compile in MultiJvm) triggeredBy (compile in Test),
      parallelExecution in Test := false,
      // make sure that MultiJvm tests are executed by the default test target,
      // and combine the results from ordinary test and multi-jvm tests
      executeTests in Test <<= (executeTests in Test, executeTests in MultiJvm) map {
        case (testResults, multiNodeResults)  =>
          val overall =
          if (testResults.overall.id < multiNodeResults.overall.id)
            multiNodeResults.overall
          else
            testResults.overall
          Tests.Output(overall,
          testResults.events ++ multiNodeResults.events,
          testResults.summaries ++ multiNodeResults.summaries)
      }
    )
}
