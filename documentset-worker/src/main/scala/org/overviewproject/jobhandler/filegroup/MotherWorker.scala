package org.overviewproject.jobhandler.filegroup

import scala.collection.mutable.{ Map, Queue }
import akka.actor._
import org.overviewproject.database.Database
import org.overviewproject.database.orm.finders.{ DocumentSetCreationJobFinder, GroupedProcessedFileFinder, FileGroupFinder, GroupedFileUploadFinder }
import org.overviewproject.database.orm.stores.{ DocumentSetCreationJobStore, DocumentSetStore, DocumentSetUserStore }
import org.overviewproject.jobhandler.JobProtocol._
import org.overviewproject.jobhandler.MessageHandlerProtocol._
import org.overviewproject.jobhandler.MessageQueueActorProtocol.StartListening
import org.overviewproject.tree.DocumentSetCreationJobType.FileUpload
import org.overviewproject.tree.Ownership
import org.overviewproject.tree.orm._
import org.overviewproject.tree.orm.DocumentSetCreationJobState.{ NotStarted, Preparing }
import org.overviewproject.tree.orm.FileJobState._
import org.overviewproject.util.Configuration
import org.overviewproject.jobhandler.filegroup.FileGroupMessageHandlerProtocol.{ Command => FileGroupCommand, ProcessFileCommand }
import org.overviewproject.util.Logger

object MotherWorkerProtocol {
  sealed trait Command
  case class StartClusteringCommand(
    fileGroupId: Long,
    title: String,
    lang: String,
    suppliedStopWords: String) extends Command
}

trait FileGroupJobHandlerComponent {
  def createFileGroupMessageHandler(jobMonitor: ActorRef): Props
  val storage: Storage

  trait Storage {
    def countFileUploads(fileGroupId: Long): Long
    def countProcessedFiles(fileGroupId: Long): Long
    def findDocumentSetCreationJobByFileGroupId(fileGroupId: Long): Option[DocumentSetCreationJob]

    def submitDocumentSetCreationJob(documentSetCreationJob: DocumentSetCreationJob): DocumentSetCreationJob
  }
}

trait MotherWorker extends Actor {
  this: FileGroupJobHandlerComponent =>

  import MotherWorkerProtocol._

  private val NumberOfDaughters = 2
  private val ClusteringQueue = Configuration.messageQueue.clusteringQueueName
  private val FileGroupQueue = Configuration.messageQueue.fileGroupQueueName

  private val freeWorkers = Queue.fill(NumberOfDaughters)(context.actorOf(createFileGroupMessageHandler(self)))
  private val busyWorkers = Map.empty[ActorRef, ProcessFileCommand]
  private val workQueue = Queue.empty[ProcessFileCommand]

  def receive = {
    case StartClusteringCommand(fileGroupId, title, lang, suppliedStopWords) =>
      submitCompleteJob(fileGroupId)

    case command: ProcessFileCommand => {
      if (!freeWorkers.isEmpty) startWork(command)
      else workQueue.enqueue(command)
    }

    case JobDone(fileGroupId) => {
      submitCompleteJob(fileGroupId)

      setFree(sender)

      if (!workQueue.isEmpty) self ! workQueue.dequeue
    }

  }

  /**
   * If all files have been uploaded, and all uploaded files have been processed,
   * the documentSetCreationJob state is `NotStarted` (ready for clustering). Otherwise the state
   * is `Preparing`
   */
  private def computeJobState(fileGroup: FileGroup): DocumentSetCreationJobState.Value =
    if ((fileGroup.state == Complete) && fileProcessingComplete(fileGroup.id)) NotStarted
    else Preparing

  /** file processing is complete when number of uploads matches number of processed files */
  private def fileProcessingComplete(fileGroupId: Long): Boolean =
    storage.countFileUploads(fileGroupId) == storage.countProcessedFiles(fileGroupId)

  private def submitCompleteJob(fileGroupId: Long): Unit = {
    if (fileProcessingComplete(fileGroupId)) {
      storage.findDocumentSetCreationJobByFileGroupId(fileGroupId) map {
        storage.submitDocumentSetCreationJob
      }
    }
  }

  private def startWork(command: ProcessFileCommand): Unit = {
    val next = freeWorkers.dequeue()
    busyWorkers += (next -> command)
    next ! command
  }

  private def setFree(worker: ActorRef): Unit = {
    busyWorkers -= worker
    freeWorkers.enqueue(worker)
  }
}

object MotherWorker {
  private class MotherWorkerImpl extends MotherWorker with FileGroupJobHandlerComponent {
    override def createFileGroupMessageHandler(jobMonitor: ActorRef): Props = FileGroupMessageHandler(jobMonitor)

    override val storage: StorageImpl = new StorageImpl

    class StorageImpl extends Storage {

      override def countFileUploads(fileGroupId: Long): Long = Database.inTransaction {
        GroupedFileUploadFinder.countsByFileGroup(fileGroupId)
      }

      override def countProcessedFiles(fileGroupId: Long): Long = Database.inTransaction {
        GroupedProcessedFileFinder.byFileGroup(fileGroupId).count
      }

      override def findDocumentSetCreationJobByFileGroupId(fileGroupId: Long): Option[DocumentSetCreationJob] =
        Database.inTransaction {
          DocumentSetCreationJobFinder.byFileGroupId(fileGroupId).headOption
        }

      override def submitDocumentSetCreationJob(documentSetCreationJob: DocumentSetCreationJob): DocumentSetCreationJob =
        Database.inTransaction {
          DocumentSetCreationJobStore.insertOrUpdate(documentSetCreationJob.copy(state = NotStarted))
        }

    }
  }

  def apply(): Props = Props[MotherWorkerImpl]
}