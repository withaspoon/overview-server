package org.overviewproject.searchindex

import org.elasticsearch.action.{ActionListener,ActionRequest,ActionRequestBuilder,ActionResponse}
import org.elasticsearch.action.search.{SearchResponse,SearchType}
import org.elasticsearch.client.Client
import org.elasticsearch.common.settings.ImmutableSettings
import org.elasticsearch.common.unit.TimeValue
import org.elasticsearch.index.query.{FilterBuilders,QueryBuilders}
import org.elasticsearch.indices.IndexMissingException
import play.api.libs.json.Json
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future,Promise}

import org.overviewproject.tree.orm.Document // FIXME should be model
import org.overviewproject.util.Logger

/** ElasticSearch index client.
  *
  * Within ElasticSearch, we use these indices and aliases:
  *
  * <ul>
  *   <li><tt>documents_v1</tt> (or <tt>_v2</tt>, etc.) is the actual index.
  *       ElasticSearchIndexClient will create <tt>documents_v1</tt> if there is
  *       no <tt>documents</tt> alias.</li>
  *   <li><tt>documents</tt> is an alias to the <tt>documents_v1</tt>.
  *       ElasticSearchIndexClient reads this alias in order to create other
  *       aliases.</li>
  *   <li><tt>documents_123</tt> is a filtered alias to <tt>documents_v1</tt>,
  *       with <tt>document_set_id = 123</tt>. ElasticSearchIndexClient creates
  *       this within addDocumentSet() and uses it within addDocuments().</li>
  * </ul>
  *
  * We can reindex seamlessly while Overview is running, like this. (All these
  * steps should take place <em>outside</em> ElasticSearchIndexClient.
  * ElasticSearchIndexClient will behave correctly at any point in this
  * process.)
  *
  * <ol>
  *   <li>Create <tt>documents_v2</tt>.</li>
  *   <li>Add a new <tt>document</tt> mapping. It should be a mirror of
  *       <tt>common/src/main/resources/documents-mapping.json</tt>.</li>
  *   <li>For each document set <tt>N</tt>, add a second alias
  *       <tt>documents_N</tt> that points to <tt>documents_v2</tt>.
  *       (So ElasticSearchIndexClient see new documents.)</li>
  *   <li>Modify the <tt>documents</tt> alias. (ElasticSearchIndexClient will
  *       write new documents to <tt>documents_v2</tt>.)</li>
  *   <li>For each document set <tt>N</tt>:
  *     <ol>
  *       <li>Skip if <tt>documents_N</tt> points to exactly
  *           <tt>documents_v2</tt> and not <tt>documents_v1</tt>.</li>
  *       <li>Index all documents in document set <tt>N</tt> into
  *           <tt>documents_v2</tt>, from scratch. (<tt>_id</tt> will
  *           ensure you overwrite rather than add documents.)</li>
  *       <li>Drop the alias from <tt>documents_N</tt> to 
  *           any index other than <tt>documents_v2</tt>.</li>
  *     </ol>
  *   </li>
  * </ol>
  *
  * This process will handle resumes and writes that happen during indexing.
  * Only when the <tt>documents_N</tt> alias points to only
  * <tt>documents_v2</tt> is document set <tt>N</tt> complete.
  */
trait ElasticSearchIndexClient extends IndexClient {
  protected val AllDocumentsAlias = "documents"
  protected val DefaultIndexName = "documents_v1"

  @volatile private var connected = false

  /** Calls connect(), then adds necessary data structures to ElasticSearch
    * if they aren't already there.
    *
    * After accessing this variable, you can assume:
    *
    * * There is a "documents_v1" index, with a "document" mapping.
    * * There is a "documents" alias to the "documents_v1" index.
    */
  protected lazy val clientFuture: Future[Client] = {
    connected = true
    connect
  }

  /** Returns an ElasticSearch client handle.
    *
    * This should call ensureInitialized() before resolving.
    */
  protected def connect: Future[Client]

  /** Releases all resources created during connect. */
  protected def disconnect: Future[Unit]

  private val DocumentSetIdField = "document_set_id"

  /** Number of documents to receive per shard per page.
    *
    * &gt; 10K seems to carry a speed penalty on a similar operation:
    * https://groups.google.com/d/topic/overview-dev/orgV1iDS9U4/discussion
    */
  private val DefaultScrollSize: Int = 5000

  /** Timeout that will kill communications from shards, in milliseconds.
    *
    * The higher this number, the more reliable Overview is. The lower the
    * number, the less time shards will maintain data structures for each
    * query.
    */
  private val ScrollTimeout: Int = 60000

  protected val DocumentTypeName = "document"
  protected def aliasName(documentSetId: Long) = s"documents_$documentSetId"

  protected lazy val Mapping = {
    val inputStream = getClass.getResourceAsStream("/documents-mapping.json")
    val source = scala.io.Source.fromInputStream(inputStream)
    source.getLines.mkString("\n")
  }

  def close: Future[Unit] = {
    if (connected) {
      disconnect
    } else {
      Future.successful(Unit)
    }
  }

  /** Executes an ElasticSearch request asynchronously.
    *
    * Returns a Future. If the request succeeds, calls onSuccess(response) and
    * the future resolves to the return value. If onSuccess() throws an
    * exception, the Future fails. If the ElasticSearch execution fails, the
    * Future fails.
    */
  private def execute[Request <: ActionRequest[Request],Response <: ActionResponse,RequestBuilder <: ActionRequestBuilder[Request,Response,RequestBuilder]](req: ActionRequestBuilder[Request,Response,RequestBuilder]): Future[Response] = {
    val promise = Promise[Response]()

    req.execute(new ActionListener[Response] {
      override def onResponse(result: Response) = promise.success(result)
      override def onFailure(t: Throwable) = promise.failure(t)
    })

    promise.future
  }

  /** Returns the name of the index that contains all new documents.
    *
    * If there is no <tt>documents</tt> alias in ElasticSearch, this method
    * will create a new <tt>documents_v1</tt> index and <tt>documents</tt>
    * alias and return <tt>"documents_v1"</tt>>
    */
  private def getAllDocumentsIndexName(client: Client): Future[String] = {
    val exists = client.admin.indices.prepareExistsAliases(AllDocumentsAlias)
    val get = client.admin.indices.prepareGetAliases(AllDocumentsAlias)

    execute(exists).map(_.isExists).flatMap(_ match {
      case true => execute(get).map(_.getAliases.keySet.toArray.head.asInstanceOf[String])
      case false => createDefaultIndexAndAlias(client).map(_ => DefaultIndexName)
    })
  }

  private def createDefaultIndexAndAlias(client: Client): Future[Unit] = {
    createDefaultIndex(client)
      .flatMap(_ => createDefaultAlias(client))
  }

  protected def defaultIndexSettings = ImmutableSettings.settingsBuilder

  private def createDefaultIndex(client: Client): Future[Unit] = {
    val req = client.admin.indices.prepareCreate(DefaultIndexName)
      .setSettings(defaultIndexSettings)
      .addMapping(DocumentTypeName, Mapping)

    execute(req).map(_ => Unit)
  }

  private def createDefaultAlias(client: Client): Future[Unit] = {
    execute(client.admin.indices.prepareAliases.addAlias(DefaultIndexName, AllDocumentsAlias))
      .map(_ => Unit)
  }

  private def addDocumentSetImpl(client: Client, id: Long): Future[Unit] = {
    getAllDocumentsIndexName(client)
      .flatMap { indexName =>
        val filter = FilterBuilders.termFilter(DocumentSetIdField, id)
        val alias = client.admin.indices.prepareAliases()
          .addAlias(indexName, aliasName(id), filter)

        execute(alias).map(_.isAcknowledged)
      }
      .map(_ => Unit)
  }

  private def removeDocumentSetImpl(client: Client, id: Long): Future[Unit] = {
    // The index must create if we're to delete it. So let's create it if it
    // does not exist.
    getAllDocumentsIndexName(client)
      .flatMap { indexName =>
        val unalias = client.admin.indices.prepareAliases()
          .removeAlias(indexName, aliasName(id))

        val delete = client.prepareDeleteByQuery(AllDocumentsAlias)
          .setTypes(DocumentTypeName)
          .setQuery(QueryBuilders.termQuery(DocumentSetIdField, id))

        Future.sequence(Seq(execute(unalias), execute(delete)))
      }
      .map(_ => Unit)
  }

  private def addDocumentsImpl(client: Client, documents: Iterable[Document]): Future[Unit] = {
    val bulkBuilder = client.prepareBulk()

    /*
     * We write to the all-documents alias, not the docset alias. That's a
     * side-effect of the design, but if we change the design, we must keep
     * that property: "It is an error to index to an alias which points to more
     * than one index."
     * http://www.elasticsearch.org/guide/en/elasticsearch/reference/current/indices-aliases.html
     */

    documents.foreach { document =>
      bulkBuilder.add(
        client.prepareIndex(AllDocumentsAlias, DocumentTypeName)
          .setSource(Json.obj(
            "document_set_id" -> document.documentSetId,
            "id" -> document.id,
            "text" -> document.text,
            "title" -> document.title,
            "supplied_id" -> document.suppliedId
          ).toString)
          .request
      )
    }

    execute(bulkBuilder).map { response =>
      if (response.hasFailures) {
        throw new Exception(response.buildFailureMessage)
      } else {
        Unit
      }
    }
  }

  private def searchForIdsImpl(client: Client, documentSetId: Long, q: String, scrollSize: Int): Future[Seq[Long]] = {
    val query = QueryBuilders.queryString(q)

    // ElasticSearch shouldn't sort results. [refs #83002148]
    val filter = FilterBuilders.queryFilter(query)

    val req = client.prepareSearch(aliasName(documentSetId))
      .setSearchType(SearchType.SCAN)
      .setScroll(new TimeValue(ScrollTimeout))
      .setTypes(DocumentTypeName)
      .setFilter(filter)
      .setSize(scrollSize)
      .addField("id") // We started using _id on 2015-01-12 but some end-users
                      // might not have reindexed yet. So we index and store
                      // "id" instead.

    def throwIfError(response: SearchResponse): Unit = {
      response.getShardFailures.headOption match {
        case None => ()
        case Some(failure) => throw new Exception(failure.reason)
      }
    }

    def responseToLongs(response: SearchResponse): Seq[Long] = {
      throwIfError(response)

      // Casting to String then Long because ElasticSearch sends JSON and
      // forgets the type. It's sometimes Integer, sometimes Long.
      // https://groups.google.com/forum/#!searchin/elasticsearch/getsource$20integer$20long/elasticsearch/jxIY22TmA8U/PyqZPPyYQ0gJ
      response.getHits
        .getHits
        .map(_.field("id").value[Object].toString.toLong)
        .toSeq
    }

    def step(accumulator: Seq[Seq[Long]], response: SearchResponse): Future[Seq[Long]] = {
      val newLongs = responseToLongs(response)
      val newAccumulator = accumulator :+ newLongs
      if (newLongs.isEmpty) {
        Future.successful(newAccumulator.flatten)
      } else {
        requestScroll(response.getScrollId()).flatMap(step(newAccumulator, _))
      }
    }

    def requestScroll(scrollId: String): Future[SearchResponse] = {
      val scrollReq = client
        .prepareSearchScroll(scrollId)
        .setScroll(new TimeValue(ScrollTimeout))

      execute(scrollReq)
    }

    execute(req)
      .flatMap { response =>
        throwIfError(response)
        if (response.getHits.totalHits == 0) {
          Future.successful(Seq())
        } else {
          requestScroll(response.getScrollId()).flatMap(step(Seq(), _))
        }
      }
      .recover {
        case e: IndexMissingException =>
          Logger.forClass(getClass).warn("IndexMissingException on index {}", aliasName(documentSetId))
          Seq()
      }
  }

  private def refreshImpl(client: Client): Future[Unit] = {
    val req = client.admin.indices.prepareRefresh(AllDocumentsAlias)

    execute(req).map { response =>
      response.getShardFailures.headOption match {
        case None => Unit
        case Some(failure) => throw new Exception(failure.reason)
      }
    }
  }

  override def addDocumentSet(id: Long) = clientFuture.flatMap(addDocumentSetImpl(_, id))
  override def removeDocumentSet(id: Long) = clientFuture.flatMap(removeDocumentSetImpl(_, id))
  override def addDocuments(documents: Iterable[Document]) = clientFuture.flatMap(addDocumentsImpl(_, documents))
  override def refresh = clientFuture.flatMap(refreshImpl(_))

  override def searchForIds(documentSetId: Long, q: String) = {
    clientFuture.flatMap(searchForIdsImpl(_, documentSetId, q, DefaultScrollSize))
  }

  def searchForIds(documentSetId: Long, q: String, scrollSize: Int) = {
    clientFuture.flatMap(searchForIdsImpl(_, documentSetId, q, scrollSize))
  }
}
