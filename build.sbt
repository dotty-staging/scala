val dottyVersion = dottyLatestNightlyBuild.get

lazy val scalap = project
  .in(file("src/scalap"))
  .settings(
    scalaVersion := dottyVersion,
    scalacOptions := List("-language:Scala2"),

    sourceDirectory in Compile := baseDirectory.value,
    target := (baseDirectory in ThisBuild).value / "target" / thisProject.value.id,

    autoScalaLibrary := false,
    libraryDependencies += "org.scala-lang" % "scala-compiler" % "2.12.5"
  )
