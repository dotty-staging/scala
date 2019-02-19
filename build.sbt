lazy val library = project
  .in(file("src/library"))
  .settings(
    scalacOptions ++= Seq(
      "-feature",
      "-deprecation",
      "-unchecked",
      // "-Xfatal-warnings",
      "-language:existentials,higherKinds,implicitConversions",
      // "-Ycheck:all"
    ),
    sourceDirectory in Compile := baseDirectory.value,
    target := (baseDirectory in ThisBuild).value / "target" / thisProject.value.id,
    compileOrder := CompileOrder.JavaThenScala,
    // scalacOptions in Compile ++= Seq("-sourcepath", (scalaSource in Compile).value.toString),
    excludeFilter in unmanagedSources := HiddenFileFilter || "AnyVal.scala",
    // autoScalaLibrary := false,
  )
