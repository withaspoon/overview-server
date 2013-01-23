package persistence

import org.overviewproject.postgres.LO
import org.overviewproject.test.DbSpecification
import org.overviewproject.test.DbSetup._
import org.overviewproject.database.DB

class UploadedFileLoaderSpec extends DbSpecification {

  step(setupDb)

  "UploadedFileLoader" should {

    trait UploadedFileContext extends DbTestContext {
      val size = 1999l
      val contentType = "application/octet-stream"

      var uploadedFileId: Long = _
      var oid: Long = _

      override def setupWithDb {
        implicit val pgc = DB.pgConnection
        LO.withLargeObject { lo =>
          uploadedFileId = insertUploadedFile(lo.oid, "content-disposition", contentType, size)
          oid = lo.oid
        }
      }
    }

    "load uploaded file values" in new UploadedFileContext {
      val uploadedFile = EncodedUploadFile.load(uploadedFileId)
      uploadedFile.contentsOid must beSome.like { case uoid => uoid must be equalTo oid }
      uploadedFile.contentType must be equalTo contentType
      uploadedFile.size must be equalTo size
    }
    
  }

  step(shutdownDb)

  case class TestUploadFile(contentType: String) extends EncodedUploadFile {
    val contentsOid: Option[Long] = None
    val size: Long = 100
  }

  "UploadedFile" should {

    "return None if no encoding can be found" in {
      val uploadedFile = TestUploadFile("application/octet-stream")
      uploadedFile.encoding must beNone
    }

    "return specified encoding" in {
      val encoding = "someEncoding"
      val uploadedFile = TestUploadFile("application/octet-stream; charset=" + encoding)
      uploadedFile.encoding must beSome.like { case c => c must be equalTo (encoding) }
    }
  }
}