package scastie.metals

import java.nio.file.Files
import java.nio.file.Path
import scala.concurrent.duration.*
import scala.concurrent.Future
import scala.jdk.CollectionConverters._
import scala.meta.internal.metals.Embedded
import scala.meta.internal.metals.MtagsBinaries
import scala.meta.internal.metals.MtagsResolver
import scala.util.control.NonFatal

import cats.data.EitherT
import cats.data.OptionT
import cats.effect.{Async, Sync}
import cats.syntax.all._
import com.evolutiongaming.scache.Cache
import com.olegych.scastie.api._
import com.olegych.scastie.api.ScalaTarget._
import coursierapi.{Dependency, Fetch}
import org.slf4j.LoggerFactory

/*
 * MetalsDispatcher is responsible for managing the lifecycle of presentation compilers.
 *
 * Each metals client configuration requires separate presentation compilers
 * to support cabailities for 3rd party capabilities.
 *
 * @param cache - cache used for managing presentation compilers
 */
class MetalsDispatcher[F[_]: Async](cache: Cache[F, ScastieMetalsOptions, ScastiePresentationCompiler]) {
  private val logger                = LoggerFactory.getLogger(getClass)
  private val presentationCompilers = PresentationCompilers[F]()

  private val mtagsResolver          = MtagsResolver.default()
  private val metalsWorkingDirectory = Files.createTempDirectory("scastie-metals")
  private val supportedVersions      = Set("2.12", "2.13", "3")

  /*
   * If `configuration` is supported returns either `FailureType` in case of error during its initialization
   * or fetches the `ScastiePresentationCompiler` from guava cache.
   * If the key is not present in guava cache, it is initialized
   *
   * @param configuration - scastie client configuration
   * @returns `EitherT[F, FailureType, ScastiePresentationCompiler]`
   */
  def getCompiler(configuration: ScastieMetalsOptions): EitherT[F, FailureType, ScastiePresentationCompiler] = EitherT {
    if !isSupportedVersion(configuration) then
      Async[F].pure(
        Left(
          PresentationCompilerFailure(
            s"Interactive features are not supported for Scala ${configuration.scalaTarget.binaryScalaVersion}."
          )
        )
      )
    else
      Sync[F].delay(
        mtagsResolver
          .resolve(configuration.scalaTarget.scalaVersion)
          .toRight(
            PresentationCompilerFailure(
              s"Mtags couldn't be resolved for target: ${configuration.scalaTarget.scalaVersion}."
            )
          )
      ) >>= (_.traverse(mtags => cache.getOrUpdate(configuration)(initializeCompiler(configuration, mtags)))
        .recoverWith { case NonFatal(e) =>
          logger.error(e.getMessage)
          PresentationCompilerFailure(e.getMessage).asLeft.pure[F]
        })
  }

  /*
   * Checks if given configuration is supported. Currently it is based on scala binary version.
   * We are supporting only those versions which are defined in `supportedVersions`.
   */
  private def isSupportedVersion(configuration: ScastieMetalsOptions): Boolean =
    supportedVersions.contains(configuration.scalaTarget.binaryScalaVersion)

  /*
   * This is workaround for bad scaladex search UI in scastie.
   * We must properly handle non compatibile library versions.
   * In sbt it is automatically resolved but here, we manually specify scala target.
   */
  def areDependenciesSupported(configuration: ScastieMetalsOptions): EitherT[F, FailureType, Boolean] =
    def scalaTargetString(scalaTarget: ScalaTarget): String =
      s"${scalaTarget.scalaVersion}" ++ (if scalaTarget.targetType == ScalaTargetType.JS then
                                           s" ${scalaTarget.targetType}"
                                         else "")

    def checkScalaVersionCompatibility(scalaTarget: ScalaTarget): Boolean =
      if configuration.scalaTarget.binaryScalaVersion.startsWith("3") then
        scalaTarget.binaryScalaVersion.startsWith("2.13") || scalaTarget.targetType == ScalaTargetType.Scala3
      else scalaTarget.binaryScalaVersion == configuration.scalaTarget.binaryScalaVersion

    def checkScalaJsCompatibility(scalaTarget: ScalaTarget): Boolean =
      if configuration.scalaTarget.targetType == ScalaTargetType.JS then scalaTarget.targetType == ScalaTargetType.JS
      else true

    val misconfiguredLibraries = configuration.dependencies
      .filterNot(l => checkScalaVersionCompatibility(l.target) && checkScalaJsCompatibility(l.target))

    Option
      .when(misconfiguredLibraries.nonEmpty) {
        val errorString = misconfiguredLibraries
          .map(l =>
            s"${l.toString} dependency  binary version is: ${scalaTargetString(l.target)} while scastie is set to: ${scalaTargetString(configuration.scalaTarget)}"
          )
          .mkString("\n")
        PresentationCompilerFailure(s"Misconfigured dependencies: $errorString")
      }
      .toLeft(true)
      .toEitherT

  /*
   * Initializes the compiler with proper classpath and version
   *
   * @param configuration - scastie client configuration
   * @param mtags - binaries of mtags for specific scala version
   */
  private def initializeCompiler(
    configuration: ScastieMetalsOptions,
    mtags: MtagsBinaries
  ): F[ScastiePresentationCompiler] = (
    getDependencyClasspath(configuration.dependencies, getScalaJsDependencies(configuration.scalaTarget)),
    getScalaLibrary(configuration.scalaTarget),
    getScalaTargetSources(configuration.scalaTarget)
  ).mapN(_ ++ _ ++ _) >>= { classpath =>
    val scalaVersion = configuration.scalaTarget.scalaVersion
    presentationCompilers
      .createPresentationCompiler(classpath.toSeq, scalaVersion, mtags)
      .map(ScastiePresentationCompiler.apply)
  }

  /*
   * Maps the artifact name to proper artifact name.
   */
  private def artifactWithBinaryVersion(artifact: String, target: ScalaTarget): String = target match
    case Js(scalaVersion, scalaJsVersion) =>
      val scalaJsBinaryVersion =
        if scalaJsVersion.startsWith("1") then "1"
        else scalaJsVersion.split('.').take(2).mkString(".")

      s"${artifact}_sjs${scalaJsBinaryVersion}_${target.binaryScalaVersion}"
    case other => s"${artifact}_${target.binaryScalaVersion}"

  /*
   * Fetches scala library for given `scalaTarget`
   *
   * @param scalaTarget - scala target for scastie client configuration
   * @returns paths of downloaded files
   */
  private def getScalaLibrary(scalaTarget: ScalaTarget): F[Set[Path]] =
    Sync[F].delay { Embedded.scalaLibrary(scalaTarget.scalaVersion).toSet }

  /*
   * Fetches scala sources for given `scalaTarget`
   *
   * @param scalaTarget - scala target for scastie client configuration
   * @returns paths of downloaded files
   */
  private def getScalaTargetSources(scalaTarget: ScalaTarget): F[Set[Path]] = Sync[F].delay {
    if scalaTarget.scalaVersion.startsWith("3") then Embedded.downloadScala3Sources(scalaTarget.scalaVersion).toSet
    else Embedded.downloadScalaSources(scalaTarget.scalaVersion).toSet
  }

  /*
   * Fetches scalajs sources when `scalaTarget` is `scalajs`
   *
   * @param scalaTarget - scala target for scastie client configuration
   * @returns paths of downloaded files
   */
  private def getScalaJsDependencies(scalaTarget: ScalaTarget): Set[Dependency] = scalaTarget match
    case Js(scalaVersion, scalaJsVersion) if scalaVersion.startsWith("3") =>
      Set(Dependency.of("org.scala-js", "scalajs-library_2.13", scalaJsVersion))
    case Js(scalaVersion, scalaJsVersion) => Set(
        Dependency.of(
          "org.scala-js",
          artifactWithBinaryVersion("scalajs-library", ScalaTarget.Jvm(scalaVersion)),
          scalaJsVersion
        )
      )
    case _ => Set.empty

  /*
   * Fetches scala dependencies classpath
   *
   * @param dependencies - scala dependencies used in scastie client configuration
   * @param extraDependencies - extra dependencies to fetch
   * @returns paths of downloaded files
   */
  private def getDependencyClasspath(
    dependencies: Set[ScalaDependency],
    extraDependencies: Set[Dependency]
  ): F[Set[Path]] = Sync[F].delay {
    val dep = dependencies.map { case ScalaDependency(groupId, artifact, target, version) =>
      Dependency.of(groupId, artifactWithBinaryVersion(artifact, target), version)
    }.toSeq ++ extraDependencies

    Fetch
      .create()
      .addRepositories(Embedded.repositories*)
      .withDependencies(dep*)
      .withClassifiers(Set("sources").asJava)
      .withMainArtifacts()
      .fetch()
      .asScala
      .map(file => Path.of(file.getPath))
      .toSet
  }

}
