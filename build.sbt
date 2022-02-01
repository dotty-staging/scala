lazy val scalap = project
  .in(file("src/scalap"))
  .settings(
    scalacOptions ++= List("-source:3.0-migration"),

    (Compile / sourceDirectory) := baseDirectory.value,
    target := (ThisBuild / baseDirectory).value / "target" / thisProject.value.id,

    libraryDependencies += "org.scala-lang" % "scala-compiler" % "2.13.0"
  )
