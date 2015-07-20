import sbt._
import Keys._

object PigBuild extends Build {

  val possibleBackends = List("flink","flinks","spark","sparks","scala")

  val flinkSettings = Map(
    "name"  -> "flink",
    "runClass" -> "dbis.pig.tools.FlinkRun",
    "templateFile" -> "flink-template.stg"
  )

  val flinksSettings = Map(
    "name"  -> "flinks",
    "runClass" -> "dbis.pig.tools.FlinkRun",
    "templateFile" -> "flinks-template.stg"
  )

  val sparkSettings = Map(
    "name"  -> "spark",
    "runClass" -> "dbis.pig.tools.SparkRun",
    "templateFile" -> "spark-template.stg"
  )

  val sparksSettings = Map(
    "name"  -> "sparks",
    "runClass" -> "dbis.pig.tools.SparkRun",
    "templateFile" -> "sparks-template.stg"
  )

  val flinkBackend      = Map("flink" -> flinkSettings, "default" -> flinkSettings)
  val flinksBackend     = Map("flinks" -> flinksSettings, "default" -> flinksSettings)
  val sparkBackend      = Map("spark" -> sparkSettings, "default" -> sparkSettings)
  val sparksBackend     = Map("sparks" -> sparksSettings, "default" -> sparksSettings)
  val scalaBackend      = Map("flink" -> flinkSettings, "spark" -> sparkSettings, "default" -> sparkSettings)

  def backendDependencies(backend: String): Seq[sbt.ModuleID] = backend match {
    case "flink" | "flinks" => Seq (
      Dependencies.flinkDist % "provided" from "http://cloud01.prakinf.tu-ilmenau.de/flink-0.9.jar"
    )
    case "spark" | "sparks" => Seq (
      Dependencies.sparkCore % "provided",
      Dependencies.sparkSql % "provided"
    )
    case "scala" => Seq(
      Dependencies.flinkDist % "provided" from "http://cloud01.prakinf.tu-ilmenau.de/flink-0.9.jar",
      Dependencies.sparkCore % "provided",
      Dependencies.sparkSql % "provided"
    )
    case _ => throw new Exception(s"Backend $backend not available")
  }

  def excludes(backend: String): Seq[sbt.Def.SettingsDefinition] = backend match{
    case "flink" => { Seq(
      excludeFilter in unmanagedSources :=
      HiddenFileFilter            ||
      "*SparkRun.scala"           ||
      "*SparkCompileIt.scala"     ||
      "*SparkCompileSpec.scala"   ||
      "*SparksCompileIt.scala"    ||
      "*SparksCompileSpec.scala"  ||
      "*FlinksCompileIt.scala"    ||
      "*FlinksCompileSpec.scala"  ||
      "*StreamInputFuncs.scala",
      excludeFilter in unmanagedResources := 
      HiddenFileFilter || 
      "spark-template.stg" ||
      "sparks-template.stg" ||
      "flinks-template.stg"      
    )}
    case "flinks" => { Seq(
      excludeFilter in unmanagedSources :=
      HiddenFileFilter            ||
      "*SparkRun.scala"           ||
      "*SparkCompileIt.scala"     ||
      "*SparkCompileSpec.scala"   ||
      "*SparksCompileIt.scala"    ||
      "*SparksCompileSpec.scala"  ||
      "*FlinkCompileIt.scala"     ||
      "*FlinkCompileSpec.scala"   ||
      "*Storage.scala",
      excludeFilter in unmanagedResources := 
      HiddenFileFilter      || 
      "spark-template.stg"  ||
      "sparks-template.stg" ||
      "flink-template.stg"      
    )}
    case "spark" =>{ Seq(
      excludeFilter in unmanagedSources :=
      HiddenFileFilter            ||
      "*SparksCompileIt.scala"    ||
      "*SparksCompileSpec.scala"  ||
      "*FlinkRun.scala"           ||
      "*FlinkCompileIt.scala"     ||
      "*FlinkCompileSpec.scala"   ||
      "*FlinksCompileIt.scala"    ||
      "*FlinksCompileSpec.scala",
      excludeFilter in unmanagedResources := 
      HiddenFileFilter      || 
      "sparks-template.stg" ||
      "flinks-template.stg" ||
      "flink-template.stg"
    )}
    case "sparks" =>{ Seq(
      excludeFilter in unmanagedSources :=
      HiddenFileFilter            ||
      "*SparkCompileIt.scala"     ||
      "*SparkCompileSpec.scala"   ||
      "*FlinkRun.scala"           ||
      "*FlinkCompileIt.scala"     ||
      "*FlinkCompileSpec.scala"   ||
      "*FlinksCompileIt.scala"    ||
      "*FlinksCompileSpec.scala",
      excludeFilter in unmanagedResources := 
      HiddenFileFilter      || 
      "spark-template.stg"  ||
      "flinks-template.stg" ||
      "flink-template.stg"
    )}
    case "scala" => excludeFilter in unmanagedSources := HiddenFileFilter
    case _ => throw new Exception(s"Backend $backend not available")
  }
}

object Dependencies {
  // Versions
  lazy val scalaVersion =       "2.11.7"
  lazy val scalaTestVersion =   "2.2.0"
  lazy val scalaPCVersion =     "1.0.3"
  lazy val scalaIoFileVersion = "0.4.3-1"
  lazy val jlineVersion =       "2.12.1"
  lazy val graphVersion =       "1.9.2"
  lazy val sparkVersion =       "1.4.0"
  lazy val flinkVersion =       "0.9.0"
//  lazy val flinkVersion =       "0.10-SNAPSHOT"
  lazy val scoptVersion =       "3.3.0"
  lazy val scalastiVersion =    "2.0.0"
  lazy val jeromqVersion =      "0.3.4"
  lazy val kiamaVersion =       "1.8.0"

  // Libraries
  val scalaCompiler = "org.scala-lang" % "scala-compiler" % scalaVersion
  val scalaTest = "org.scalatest" %% "scalatest" % scalaTestVersion
  val scalaParserCombinators = "org.scala-lang.modules" %% "scala-parser-combinators" % scalaPCVersion
  val scalaIoFile = "com.github.scala-incubator.io" %% "scala-io-file" % scalaIoFileVersion
  val jline = "jline" % "jline" % jlineVersion
  val graphCore = "com.assembla.scala-incubator" %% "graph-core" % graphVersion
  val sparkCore = "org.apache.spark" %% "spark-core" % sparkVersion
  val sparkSql = "org.apache.spark" %% "spark-sql" % sparkVersion
  val flinkDist = "org.apache.flink" %% "flink-dist" % flinkVersion
  val scopt = "com.github.scopt" %% "scopt" % scoptVersion
  val scalasti = "org.clapper" %% "scalasti" % scalastiVersion
  val jeromq = "org.zeromq" % "jeromq" % jeromqVersion
  val kiama = "com.googlecode.kiama" %% "kiama" % kiamaVersion
  val typesafe = "com.typesafe" % "config" % "1.3.0"

  // Projects
  val rootDeps = Seq(
    jline, 
    scalaTest % "test,it" withSources(),
    scalaParserCombinators withSources(),
    scalaCompiler,
    scopt,
    scalaIoFile,
    scalasti,
    kiama,
    typesafe
  )
  val sparkDeps = Seq(
    scalaTest % "test" withSources(),
    scalaCompiler,
    sparkCore % "provided",
    sparkSql % "provided"
  )
  val flinkDeps = Seq(
    scalaTest % "test" withSources(),
    scalaCompiler,
    jeromq,
    flinkDist % "provided" from "http://cloud01.prakinf.tu-ilmenau.de/flink-0.9.jar"
//    flinkDist % "provided" from "file:/home/blaze/Documents/TU_Ilmenau/Masterthesis/projects/flink/build-target/lib/flink-dist-0.10-SNAPSHOT.jar"
  )
}
