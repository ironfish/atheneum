publishLocal := {}
publish := {}
lazy val base = (project in file(".")).aggregate(atheneum_common, atheneum_member, atheneum_hobby)
lazy val atheneum_common = project
lazy val atheneum_member = project.dependsOn(atheneum_common % "test->test;compile->compile")
lazy val atheneum_hobby = project.dependsOn(atheneum_common % "test->test;compile->compile")

 
