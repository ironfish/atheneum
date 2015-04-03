import com.typesafe.sbt.SbtMultiJvm
import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys.MultiJvm

name := "atheneum-member"
version := "0.1.0-SNAPSHOT"
Atheneum.settings
Atheneum.multiJvmSettings
libraryDependencies ++= Atheneum.dependencies
configs(MultiJvm)
