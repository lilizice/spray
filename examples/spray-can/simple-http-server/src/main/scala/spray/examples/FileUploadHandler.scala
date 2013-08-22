package spray.examples

import akka.actor._
import akka.util.duration._
import akka.util.Duration
import java.io.{FileInputStream, FileOutputStream, File}
import org.jvnet.mimepull.{MIMEPart, MIMEMessage}
import org.parboiled.common.FileUtils
import spray.http._
import MediaTypes._
import HttpHeaders._
import parser.HttpParser
import HttpHeaders.RawHeader
import spray.io.CommandWrapper
import spray.util.SprayActorLogging


class FileUploadHandler(client: ActorRef, start: ChunkedRequestStart) extends Actor with SprayActorLogging {
  import start.request._
  client ! CommandWrapper(SetRequestTimeout(Duration.Inf)) // cancel timeout

  val tmpFile = File.createTempFile("chunked-receiver", ".tmp", new File("/tmp"))
  tmpFile.deleteOnExit()
  val output = new FileOutputStream(tmpFile)
  val Some(HttpHeaders.`Content-Type`(ContentType(multipart: MultipartMediaType, _))) = header[HttpHeaders.`Content-Type`]
  val boundary = multipart.parameters("boundary")

  log.info("Got start of chunked request " + method + ' ' + uri + " with multipart boundary '" + boundary + "' writing to " + tmpFile)
  var bytesWritten = 0

  def receive = {
    case c: MessageChunk =>
      log.info("Got " + c.body.size + " bytes of chunked request " + method + ' ' + uri)

      output.write(c.body)
      bytesWritten += c.body.size

    case e: ChunkedMessageEnd =>
      log.info("Got end of chunked request " + method + ' ' + uri)
      output.close()

      client ! HttpResponse(status = 200, entity = renderResult())
      client ! CommandWrapper(SetRequestTimeout(2.seconds)) // reset timeout to original value
      tmpFile.delete()
      context.stop(self)
  }

  import collection.JavaConverters._
  def renderResult(): HttpEntity = {
    val message = new MIMEMessage(new FileInputStream(tmpFile), boundary)
    val parts = message.getAttachments.asScala.toSeq

    HttpEntity(`text/html`,
      <html>
        <body>
          <p>Got {bytesWritten} bytes</p>
          <h3>Parts</h3>
          {
            parts.map { part =>
              val name = fileNameForPart(part).getOrElse("<unknown>")
              <div>{name}: {part.getContentType} of size {FileUtils.readAllBytes(part.read()).size}</div>
            }
          }
        </body>
      </html>.toString()
    )
  }
  def fileNameForPart(part: MIMEPart): Option[String] =
    for {
      dispHeader <- part.getHeader("Content-Disposition").asScala.toSeq.lift(0)
      Right(disp: `Content-Disposition`) = HttpParser.parseHeader(RawHeader("Content-Disposition", dispHeader))
      name <- disp.parameters.get("filename")
    } yield name
}
