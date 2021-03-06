package com.overviewdocs.background.filegroupcleanup

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

import com.overviewdocs.database.HasDatabase
import com.overviewdocs.blobstorage.BlobStorage
import com.overviewdocs.models.tables.GroupedFileUploads


/**
 * Delete [[GroupedFileUpload]]s and their contents.
 */
trait GroupedFileUploadRemover extends HasDatabase {
  import database.api._

  def removeFileGroupUploads(fileGroupId: Long): Future[Unit] = {
    for {
      c <- deleteContents(fileGroupId)
      g <- deleteGroupedFileUploads(fileGroupId)  
    } yield ()
  }

  private def uploadQuery(fileGroupId: Long) = GroupedFileUploads.filter(_.fileGroupId === fileGroupId)

  private def deleteContents(fileGroupId: Long): Future[Unit] = {
    findContentOids(fileGroupId).flatMap { oids =>
      val contentLocations = oids.map(oid => s"pglo:$oid")
      blobStorage.deleteMany(contentLocations)
    }
  }

  private def findContentOids(fileGroupId: Long): Future[Seq[Long]] = {
    database.seq(uploadQuery(fileGroupId).map(_.contentsOid))
  }

  private def deleteGroupedFileUploads(fileGroupId: Long): Future[Unit] = {
    database.delete(uploadQuery(fileGroupId))
  }

  protected val blobStorage: BlobStorage
}

object GroupedFileUploadRemover {
  def apply(): GroupedFileUploadRemover = new GroupedFileUploadRemoverImpl

  private class GroupedFileUploadRemoverImpl extends GroupedFileUploadRemover {
    override protected val blobStorage = BlobStorage
  }
}
