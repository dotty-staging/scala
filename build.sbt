lazy val scalap = project
  .in(file("src/scalap"))
  .settings(
    scalacOptions ++= List("-language:Scala2Compat"),

    sourceDirectory in Compile := baseDirectory.value,
    target := (baseDirectory in ThisBuild).value / "target" / thisProject.value.id,

    autoScalaLibrary := false,
    libraryDependencies += "org.scala-lang" % "scala-compiler" % "2.13.0"
  )
