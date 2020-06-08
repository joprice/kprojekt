package com.github.gchudnov.kprojekt

import java.io.File

import com.github.gchudnov.kprojekt.encoder.Encoder
import com.github.gchudnov.kprojekt.formatter.{ Bundler, Folder, FolderConfig }
import com.github.gchudnov.kprojekt.naming.{ NameConfig, Namer }
import com.github.gchudnov.kprojekt.parser.Parser
import scopt.{ OParser, OParserBuilder }
import zio.logging.slf4j.Slf4jLogger
import zio.{ ExitCode, ZEnv, ZIO }

/**
 * Command-Line Application for topology parser
 *
 * building an image:
 * sbt 'show graalvm-native-image:packageBin'
 * OR
 * sbt cli/assembly
 *
 * bloop run cli -m com.github.gchudnov.kprojekt.Cli
 * bloop run cli -m com.github.gchudnov.kprojekt.Cli -- /path/to/toplogogy.log
 */
object Cli extends zio.App {

  final case class AppConfig(topologyFile: File = new File("."), isVerbose: Boolean = false)

  val builder: OParserBuilder[AppConfig] = OParser.builder[AppConfig]

  val parser: OParser[Unit, AppConfig] = {
    import builder._
    OParser.sequence(
      programName(BuildInfo.name),
      head(BuildInfo.name, BuildInfo.version),
      help("help").text("prints this usage text"),
      opt[Unit]("verbose")
        .action((_, c) => c.copy(isVerbose = true))
        .text("verbose mode"),
      arg[File]("<file>")
        .required()
        .action((x, c) => c.copy(topologyFile = x))
        .text("path to topology description"),
      version("version")
    )
  }

  override def run(args: List[String]): ZIO[ZEnv, Nothing, ExitCode] = {
    val logEnv = Slf4jLogger.make(logFormat = (_, logEntry) => logEntry)

    val parseEnv  = Parser.live
    val nameEnv   = NameConfig.live >>> Namer.live
    val foldEnv   = FolderConfig.live >>> Folder.live
    val encEnv    = nameEnv ++ foldEnv >>> Encoder.live
    val bundleEnv = logEnv >>> Bundler.live
    val projEnv   = (parseEnv ++ encEnv ++ bundleEnv) >>> Projektor.live

    val env = projEnv

    val program = for {
      config <- ZIO.fromOption(OParser.parse(parser, args, AppConfig()))
      _      <- Projektor.run(config.topologyFile)
    } yield ()

    program
    //      .flatMapError(it => Logging.error(it.toString).map(_ => it))
      .provideLayer(env)
      .fold(_ => ExitCode.failure, _ => ExitCode.success)
  }
}
