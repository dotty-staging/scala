lazy val scalap = project
  .in(file("src/scalap"))
  .settings(
    scalacOptions ++= List("-source:3.0-migration"),

    sourceDirectory in Compile := baseDirectory.value,
    target := (baseDirectory in ThisBuild).value / "target" / thisProject.value.id,

    libraryDependencies += "org.scala-lang" % "scala-compiler" % "2.13.0"
  )
