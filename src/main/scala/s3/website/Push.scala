package s3.website

import s3.website.model.Site._
import scala.concurrent.{ExecutionContextExecutor, Future, Await}
import scala.collection.parallel.ForkJoinTaskSupport
import scala.concurrent.forkjoin.ForkJoinPool
import scala.concurrent.duration._
import com.lexicalscope.jewel.cli.CliFactory
import scala.language.postfixOps
import s3.website.Diff.resolveDiff
import s3.website.S3.{resolveS3Files, upload}
import scala.concurrent.ExecutionContext.fromExecutor
import java.util.concurrent.Executors.newFixedThreadPool
import s3.website.model.LocalFile.resolveLocalFiles
import scala.collection.parallel.immutable.ParSeq
import java.util.concurrent.ExecutorService
import s3.website.model.Site

object Push {

  def pushSite(implicit site: Site, executor: ExecutionContextExecutor): Int = {
    println(s"Deploying ${site.rootDirectory}/* to ${site.config.s3_bucket}")

    val errorsOrUploadReports = for {
      s3Files    <- resolveS3Files.right
      localFiles <- resolveLocalFiles.right
    } yield {
      val uploadFutures = resolveDiff(localFiles, s3Files).map(_.right.map(upload)).par
      uploadFutures.tasksupport_=(new ForkJoinTaskSupport(new ForkJoinPool(site.config.concurrency_level)))

      val uploadReports = uploadFutures.map(_.right.map { uploadFuture =>
        uploadFuture.map {
          case successOrFailure => successOrFailure match {
            case Right(success) => s"Successfully uploaded ${success.s3Key}"
            case Left(failure)  => s"Failed to upload ${failure.s3Key} (${failure.error.getMessage})"
          }
        }
      })
      uploadReports
    }
    errorsOrUploadReports.right foreach { uploadReports => onUploadReports(uploadReports)}
    errorsOrUploadReports.left foreach (err => println(s"Failed to push the site: ${err.message}"))
    errorsOrUploadReports.fold(_ => 1, _ => 0)
  }

  def onUploadReports(uploadReports: UploadReports)(implicit executor: ExecutionContextExecutor) {
    uploadReports foreach (_.right.foreach {
      rep => Await.ready(rep, 1 day)
    })
    uploadReports foreach (_.right.foreach(_.foreach(println)))
  }

  type UploadReports = ParSeq[Either[model.Error, Future[String]]]
  case class PushResult(threadPool: ExecutorService, uploadReports: UploadReports)

  trait CliArgs {
    import com.lexicalscope.jewel.cli.Option

    @Option def site: String
    @Option(longName = Array("config-dir")) def configDir: String
    @Option def headless: Boolean // TODO use this

  }

  def main(args: Array[String]) {
    val cliArgs = CliFactory.parseArguments(classOf[CliArgs], args:_*)
    val errorOrPushStatus = loadSite(cliArgs.configDir + "/s3_website.yml", cliArgs.site)
      .right
      .map {
      implicit site =>
        val threadPool = newFixedThreadPool(site.config.concurrency_level)
        implicit val executor = fromExecutor(threadPool)
        val pushStatus = pushSite
        threadPool.shutdownNow()
        pushStatus
    }
    errorOrPushStatus.left foreach (err => println(s"Could not load the site: ${err.message}"))
    System.exit(errorOrPushStatus.fold(_ => 1, pushStatus => pushStatus))
  }
}
