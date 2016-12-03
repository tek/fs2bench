scalaVersion := "2.11.8"

libraryDependencies ++= List(
    "org.scalaz.stream" %% "scalaz-stream" % "0.8.4a",
    "co.fs2" %% "fs2-core" % "0.9.1",
    "co.fs2" %% "fs2-cats" % "0.1.0",
    "org.specs2" %% "specs2-core" % "3.8"
  )

addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.9.3")
