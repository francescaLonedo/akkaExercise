package bank.actor.projector

import akka.NotUsed
import akka.actor.{ActorSystem, Props}
import akka.event.LoggingReceive
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.{NoOffset, Offset, TimeBasedUUID}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import bank.AppConfig.{readBatchSize, readDelay, readWindow}
import bank.actor.ReadJournalStreamManagerActor
import bank.actor.projector.EventEnvelopeSeqHandler.EventEnvelopeSeq
import bank.actor.projector.PersonEventProjectorActor.ReadOffset
import bank.actor.projector.export.PersonLogExporter
import bank.actor.write.{ActorSharding, PersonWriterActor}
import bank.domain.Person.PersonEvent

import scala.concurrent.ExecutionContext

class PersonEventProjectorActor(indexer: PersonLogExporter)
    extends ReadJournalStreamManagerActor[EventEnvelopeSeq]
    with EventEnvelopeSeqHandler[PersonEvent]
    with ActorSharding {

  override implicit val system: ActorSystem = context.system

  override implicit val mat: ActorMaterializer = ActorMaterializer()

  implicit val blockingDispatcher: ExecutionContext =
    context.system.dispatchers.lookup(id = "akka-exercise-blocking-dispatcher")

  override def preStart(): Unit = {
    context.system.scheduler.scheduleOnce(readDelay, self, ReadOffset)(system.dispatcher)
  }

  override protected def createSource(
      readJournal: CassandraReadJournal,
      offset: Offset
  ): Source[EventEnvelopeSeq, NotUsed] = {
    readJournal
      .eventsByTag(PersonWriterActor.personDetailsTag, offset)
      .groupedWithin(readBatchSize, readWindow)
      .map(EventEnvelopeSeq(_))
  }

  override def receive: Receive = LoggingReceive {
    case ReadOffset => {
      val x = indexer.readOffset() match {
        case None         => NoOffset
        case Some(offset) => offset
      }

      context.become(eventStreamStarted(x))
      startStream(x)
      log.info("({}) Stream started! Offset: {}", indexer.name, x)
    }
    case unknown =>
      log.error("({}) Received unknown message in receiveCommand (sender: {} - message: {})",
                indexer.name,
                sender,
                unknown)
  }

  def eventStreamStarted(offset: Offset): Receive = LoggingReceive {
    ({
      case groupedEvents: EventEnvelopeSeq =>
        val offset2event: Seq[(TimeBasedUUID, PersonEvent)] = extractOffsetsAndEvents(groupedEvents)
        val eventStreamMaxOffset: TimeBasedUUID = getMaxOffset(offset2event)
        val events = offset2event map (_._2)
        val originalSender = sender()

        log.info("({}) Processing batch of {} events", indexer.name, events.size)
        indexer.indexEvents(events, eventStreamMaxOffset) match {
          case Right(_) =>
            log.info("({}) Indexing operation successfully completed! Offset: {}", indexer.name, eventStreamMaxOffset)
            context.become(eventStreamStarted(eventStreamMaxOffset))
            originalSender ! AckMessage

          case Left(exception) =>
            log.error(exception, "({}) Indexing operation failed", indexer.name)
            throw exception // effect: restart actor
        }
    }: Receive) orElse manageJournalStream(offset)
  }
}

object PersonEventProjectorActor {

  val name = "person-event-projector-actor"

  def props(indexer: PersonLogExporter): Props =
    Props(new PersonEventProjectorActor(indexer))

  case object ReadOffset
}
