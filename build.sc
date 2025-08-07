import mill._
import scalalib._
import scalafmt._
import os.Path
import $packages._
import $file.`rocket-chip`.common
import $file.`rocket-chip`.cde.common
import $file.`rocket-chip`.hardfloat.common

/* for publishVersion */
import $ivy.`de.tototec::de.tobiasroeser.mill.vcs.version::0.4.0`
import de.tobiasroeser.mill.vcs.version.VcsVersion
import java.io.{BufferedReader, InputStreamReader}
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import scala.util.matching.Regex

val defaultScalaVersion = "2.13.15"
val pwd = os.Path(sys.env("MILL_WORKSPACE_ROOT"))

def defaultVersions = Map(
  "chisel"        -> ivy"org.chipsalliance::chisel:6.6.0",
  "chisel-plugin" -> ivy"org.chipsalliance:::chisel-plugin:6.6.0",
  "chiseltest"    -> ivy"edu.berkeley.cs::chiseltest:6.0.0"
)

/* resolve firtool dependency */
import $ivy.`org.chipsalliance::chisel:6.6.0`
import $ivy.`org.chipsalliance::firtool-resolver:1.3.0`

trait HasChisel extends SbtModule {
  def chiselModule: Option[ScalaModule] = None

  def chiselPluginJar: T[Option[PathRef]] = None

  def chiselIvy: Option[Dep] = Some(defaultVersions("chisel"))

  def chiselPluginIvy: Option[Dep] = Some(defaultVersions("chisel-plugin"))

  override def scalaVersion = defaultScalaVersion

  override def scalacOptions = super.scalacOptions() ++
    Agg("-language:reflectiveCalls", "-Ymacro-annotations", "-Ytasty-reader")

  override def ivyDeps = super.ivyDeps() ++ Agg(chiselIvy.get)

  override def scalacPluginIvyDeps = super.scalacPluginIvyDeps() ++ Agg(chiselPluginIvy.get)
}

object rocketchip extends $file.`rocket-chip`.common.RocketChipModule with HasChisel {

  def scalaVersion: T[String] = T(defaultScalaVersion)

  override def millSourcePath = pwd / "rocket-chip"

  def macrosModule = macros

  def hardfloatModule = hardfloat

  def cdeModule = cde

  def mainargsIvy = ivy"com.lihaoyi::mainargs:0.7.0"

  def json4sJacksonIvy = ivy"org.json4s::json4s-jackson:4.0.7"

  object macros extends Macros

  trait Macros extends  $file.`rocket-chip`.common.MacrosModule with SbtModule {

    def scalaVersion: T[String] = T(defaultScalaVersion)

    def scalaReflectIvy = ivy"org.scala-lang:scala-reflect:${defaultScalaVersion}"
  }

  object hardfloat extends $file.`rocket-chip`.hardfloat.common.HardfloatModule with HasChisel {

    def scalaVersion: T[String] = T(defaultScalaVersion)

    override def millSourcePath = pwd / "rocket-chip" / "hardfloat" / "hardfloat"

  }

  object cde extends $file.`rocket-chip`.cde.common.CDEModule with ScalaModule {

    def scalaVersion: T[String] = T(defaultScalaVersion)

    override def millSourcePath = pwd / "rocket-chip" / "cde" / "cde"
  }
}

object utility extends SbtModule with HasChisel {
  override def millSourcePath = pwd / "utility"

  override def moduleDeps = super.moduleDeps ++ Seq(rocketchip)

  override def ivyDeps = super.ivyDeps() ++ Agg(
    ivy"com.lihaoyi::sourcecode:0.4.2",
  )

  object test extends SbtTests with TestModule.ScalaTest {
    override def ivyDeps = Agg(ivy"org.scalatest::scalatest:3.2.7")
  }
 }

object macros extends ScalaModule {

  override def millSourcePath = pwd / "macros"

  override def scalaVersion: T[String] = T(defaultScalaVersion)

  override def ivyDeps = super.ivyDeps() ++ Agg(ivy"org.scala-lang:scala-reflect:${defaultScalaVersion}")

  def scalaReflectIvy = ivy"org.scala-lang:scala-reflect:${defaultScalaVersion}"
}

trait OpenTraceModule extends ScalaModule {

  def rocketModule: ScalaModule

  def utilityModule: ScalaModule

  def macrosModule: ScalaModule

  override def moduleDeps = super.moduleDeps ++ Seq(rocketModule, utilityModule, macrosModule)

  val resourcesPATH = pwd.toString() + "/src/main/resources"
  val envPATH = sys.env("PATH") + ":" + resourcesPATH

  override def forkEnv = Map("PATH" -> envPATH)
}

object OpenTrace extends OpenTraceModule with HasChisel with ScalafmtModule{

  override def millSourcePath = pwd

  def rocketModule: ScalaModule = rocketchip

  def utilityModule: ScalaModule = utility

  def macrosModule: ScalaModule = macros

  // properties may be changed by user. Use `Task.Input` here.
  def forkArgsTask = Task.Input {
    Seq(s"-Xmx${sys.props.getOrElse("jvm-xmx", "40G")}", s"-Xss${sys.props.getOrElse("jvm-xss", "256m")}")
  }

  override def forkArgs = forkArgsTask()

  override def ivyDeps = super.ivyDeps() ++ Agg(
    defaultVersions("chiseltest"),
    ivy"io.circe::circe-yaml:1.15.0",
    ivy"io.circe::circe-generic-extras:0.14.4"
  )

  override def scalacOptions = super.scalacOptions() ++ Agg("-deprecation", "-feature")

  object test extends SbtTests with TestModule.ScalaTest {
    override def ivyDeps = super.ivyDeps() ++ Agg(
      defaultVersions("chiseltest"),
    )

    override def forkArgs = forkArgsTask()

    override def scalacOptions = super.scalacOptions() ++ Agg("-deprecation", "-feature")

    val resourcesPATH = pwd.toString() + "/src/main/resources"
    val envPATH = sys.env("PATH") + ":" + resourcesPATH

    override def forkEnv = Map("PATH" -> envPATH)
  }
}