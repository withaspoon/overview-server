package models

import anorm._
import anorm.SqlParser._
import helpers.DbSetup._
import helpers.DbTestContext
import java.sql.Connection
import org.specs2.mutable.Specification
import play.api.test.FakeApplication
import play.api.Play.{ start, stop }


class PersistentDocumentListDataLoaderSpec extends Specification {

  step(start(FakeApplication()))

  "PersistentDocumentListDataLoader" should {
    
    trait NodesAndDocuments extends DbTestContext {
      lazy val dataLoader = new PersistentDocumentListDataLoader()
      lazy val documentSetId = insertDocumentSet("PersistentDocumentListDataLoaderSpec")
      lazy val nodeIds = insertNodes(documentSetId, 3) // must access nodeIds in tests to insert them in Databas
      lazy val documentIds = insertDocumentsForeachNode(documentSetId, nodeIds, 2)
      
      lazy val tag1 = insertTag(documentSetId, "tag1")
      lazy val tag2 = insertTag(documentSetId, "tag2")
      lazy val tag3 = insertTag(documentSetId, "tag3")
    }
    
    "load document data for specified nodes with no other constraints" in new NodesAndDocuments {
      val selectedNodes = nodeIds.take(2)
      val expectedDocumentIds = documentIds.take(4)
      
      val documentData = 
        dataLoader.loadSelectedDocumentSlice(documentSetId, selectedNodes, Nil, Nil, 0, 6)
        
      val node1Data = documentIds.take(2).zipWithIndex.map { case (id, i) =>
        (id, "title-" + (i + 1), "textUrl-" + (i + 1), "viewUrl-" + (i + 1))
      }
      val node2Data = documentIds.slice(2, 4).zipWithIndex.map { case (id, i) =>
        (id, "title-" + (i + 1), "textUrl-" + (i + 1), "viewUrl-" + (i + 1))
      }
        
      val expectedDocumentData = node1Data ++ node2Data
      documentData must haveTheSameElementsAs(expectedDocumentData)
      
    }

    "load document data for specified document ids with no other constraints" in new NodesAndDocuments {
      val selectedDocuments = documentIds.take(3)
      
      val persistentDocumentListDataLoader =
        new PersistentDocumentListDataLoader()

      val documentData = 
        persistentDocumentListDataLoader.
          loadSelectedDocumentSlice(documentSetId, Nil, Nil, selectedDocuments, 0, 6)
      val loadedIds = documentData.map(_._1)
      
      loadedIds must haveTheSameElementsAs(documentIds.take(3))
    }
    
    "load document data for specified tags with no other constraints" in new NodesAndDocuments {
      tagDocuments(tag1, documentIds.take(3))
      tagDocuments(tag2, documentIds.slice(2, 4))
      val tagIds = Seq(tag1, tag2, tag3)
      
      val documentData = dataLoader.loadSelectedDocumentSlice(documentSetId,
                                                              Nil, tagIds, Nil, 0, 6)
      
      val loadedIds = documentData.map(_._1)
      
      loadedIds must haveTheSameElementsAs(documentIds.take(4))
    }
    
    "load intersection of documents specified by nodes, tags,and document ids" in new NodesAndDocuments {
      val selectedNodes = nodeIds.take(2)
      val selectedDocuments = documentIds.drop(1)
      tagDocuments(tag1, documentIds)
      tagDocuments(tag2, documentIds.take(3))
      val selectedTags = Seq(tag2, tag3)
      
      val documentData = 
        dataLoader.loadSelectedDocumentSlice(documentSetId, 
                                             selectedNodes, 
        									 selectedTags,
        									 selectedDocuments, 0, 6)

      val loadedIds = documentData.map(_._1)
      
      loadedIds must haveTheSameElementsAs(documentIds.slice(1, 3))
    }
    

    "load slice of selected documents" in new NodesAndDocuments {
      val expectedDocumentIds = documentIds.slice(2, 5)

      val documentData = 
        dataLoader.loadSelectedDocumentSlice(documentSetId, nodeIds, Nil, Nil, 2, 3)
      val loadedIds = documentData.map(_._1)
      
      loadedIds must haveTheSameElementsAs(expectedDocumentIds)     
    }
    
    "return nothing if slice offset is larger than total number of Rows" in new NodesAndDocuments {
      val selectedDocuments = documentIds
      
      val documentData = 
        dataLoader.loadSelectedDocumentSlice(documentSetId, nodeIds, Nil, Nil, 10, 4)
      
      documentData must be empty
    }
    
    "return all documents if selection is empty" in new NodesAndDocuments {
      val selectedDocuments = documentIds
       
      val documentData = 
        dataLoader.loadSelectedDocumentSlice(documentSetId, Nil, Nil, Nil, 0, 6)
        
      val loadedIds = documentData.map(_._1)
      
      loadedIds must haveTheSameElementsAs(documentIds)      
    }
    
    "load only documents that belong to documentSet" in new NodesAndDocuments {
      val documentSetId2 = insertDocumentSet("Other DocumentSet")
      val nodeIds2 = insertNodes(documentSetId2, 1)
      val documentIds2 = insertDocumentsForeachNode(documentSetId2, nodeIds2, 5)
      
      val documentData = 
        dataLoader.loadSelectedDocumentSlice(documentSetId, Nil, Nil, 
                                             documentIds ++ documentIds2, 0, 20)
                                             
      val loadedIds = documentData.map(_._1)
      loadedIds must haveTheSameElementsAs(documentIds)
    }
    
    "return total number of results in selection" in new NodesAndDocuments {
      val selectedNodes = nodeIds.take(2)
      val selectedDocuments = documentIds
      tagDocuments(tag1, documentIds.take(3))
      val selectedTags = Seq(tag1, tag2)
      
      val count = dataLoader.loadCount(documentSetId, selectedNodes, selectedTags, selectedDocuments)
      
      count must be equalTo(3)
    }
    
    "return 0 count if selection result is empty" in new NodesAndDocuments {
      val selectedNodes = nodeIds.take(1)
      val selectedDocuments = documentIds.drop(3)
      
      val count = 
        dataLoader.loadCount(documentSetId, selectedNodes, Nil, selectedDocuments)
      
      count must be equalTo(0)
    }
    
    "only count results in specified document set" in new NodesAndDocuments {
      val documentSetId2 = insertDocumentSet("Other DocumentSet")
      val nodeIds2 = insertNodes(documentSetId2, 1)
      val documentIds2 = insertDocumentsForeachNode(documentSetId2, nodeIds2, 5)
      val selectedNodes = nodeIds ++ nodeIds2
      val expectedCount = documentIds.size
      
      val count = dataLoader.loadCount(documentSetId, selectedNodes, Nil, Nil)
      
      count must be equalTo(expectedCount)
    }
    
  }

  step(stop)
}