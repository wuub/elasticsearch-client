package com.sumologic.elasticsearch.akkahelpers

import akka.actor.FSM.Failure
import akka.actor.{FSM, Props}
import akka.pattern.pipe
import akka.stream.actor.ActorPublisher
import com.sumologic.elasticsearch.akkahelpers.ScanAndScrollSource.{ScanState, ScanData}
import com.sumologic.elasticsearch.restlastic.RestlasticSearchClient.ReturnTypes.{ScrollId, SearchResponse}
import com.sumologic.elasticsearch.restlastic.ScrollClient
import com.sumologic.elasticsearch.restlastic.dsl.Dsl._
import org.slf4j.LoggerFactory

/**
 * ScanAndScrollSource wraps Elasticsearch's Scroll API as a akka-streams source. By creating and subscribing to this source,
 * you will get a stream of every message in Elasticsearch matching your query. Internally, the messages are batched. The batch
 * size is configurable by setting a size parameter on the query. Externally, results are streamed message-by-message.
 *
 * The Source will only continue getting more results from Elasticsearch will downstream is consuming. In the future, we may need two enhancements:
 * - Keep alive messages to keep the source alive >1m
 * - Buffering of more messages
 * @param index Index to search
 * @param tpe Type to search
 * @param query Query -- probably want MatchAll. You can also specify that batch size
 * @param scrollSource Raw ES scroll interface
 */

class ScanAndScrollSource(index: Index, tpe: Type, query: QueryRoot, scrollSource: ScrollClient)
  extends ActorPublisher[SearchResponse]
  with FSM[ScanState, ScanData] {

  import akka.stream.actor.ActorPublisherMessage

  import ScanAndScrollSource._
  import scala.concurrent.ExecutionContext.Implicits.global

  val logger = LoggerFactory.getLogger(ScanAndScrollSource.getClass)
  override def preStart(): Unit = {
    scrollSource.startScrollRequest(index, tpe, query).map { scrollResult =>
      ScrollStarted(scrollResult)
    }.recover(recovery).pipeTo(self)
    startWith(Starting, NotReady)
  }

  when(Starting) {
    case Event(ActorPublisherMessage.Request(_), _) => stay()
    case Event(ActorPublisherMessage.Cancel, _) => stop()

    case Event(ScrollStarted(scrollId), NotReady) =>
      requestMore(scrollId)

    case Event(ScrollFailure(ex), _) =>
      onError(ScrollFailureException("Failed to start the scroll", ex))
      stop(Failure(ex))
  }

  when(Running) {
    case Event(ActorPublisherMessage.Request(_), WithIdAndData(id, data)) =>
      consumeIfPossible(id, data)

    case Event(ActorPublisherMessage.Request(_), WaitingForDataWithId(id)) =>
      // Nothing to do, just waiting for data
      stay()

    case Event(ActorPublisherMessage.Cancel, _) =>
      // TODO: cancel the scroll
      stop()

    case Event(ScrollFailure(ex), _) =>
      onError(ScrollFailureException("Failure while running the scroll", ex))
      stop(Failure(ex))

    case Event(GotData(nextId, data), WaitingForDataWithId(currentId)) =>
      if (data.length == 0) {
        onComplete()
        stop()
      } else {
        consumeIfPossible(nextId, data)
      }
  }

  whenUnhandled {
    case Event(otherEvent, otherData) =>
      logger.warn(s"Unhandled event: $otherEvent, $otherData")
      stay()

  }

  private def consumeIfPossible(id: ScrollId, data: SearchResponse) = {
    if (totalDemand > 0) {
      onNext(data)
      requestMore(id)
      goto(Running) using WaitingForDataWithId(id)
    } else {
      goto(Running) using WithIdAndData(id, data)
    }
  }

  private def requestMore(id: ScrollId) = {
    scrollSource.scroll(id).map { case (scrollId, newData) =>
      GotData(scrollId, newData)
    }.recover(recovery).pipeTo(self)
    goto(Running) using WaitingForDataWithId(id)
  }

  private val recovery: PartialFunction[Throwable, ScrollFailure] = {
    case ex => ScrollFailure(ex)
  }
}

object ScanAndScrollSource {
  def props(index: Index, tpe: Type, query: QueryRoot, scrollSource: ScrollClient) = {
    Props(new ScanAndScrollSource(index, tpe, query, scrollSource))
  }

  case class ScrollFailureException(message: String, cause: Throwable) extends Exception(message, cause)

  sealed trait ScanState
  case object Starting extends ScanState
  case object Running extends ScanState

  sealed trait ScanData
  case object NotReady extends ScanData
  case class WaitingForDataWithId(scrollId: ScrollId) extends ScanData
  case class WithIdAndData(scrollId: ScrollId, data: SearchResponse) extends ScanData

  case class ScrollStarted(scrollId: ScrollId)
  case class GotData(nextScrollId: ScrollId, data: SearchResponse)
  case class ScrollFailure(cause: Throwable)

}


