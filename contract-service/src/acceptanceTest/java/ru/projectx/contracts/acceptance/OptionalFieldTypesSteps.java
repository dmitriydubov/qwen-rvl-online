package ru.projectx.contracts.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.spi.ILoggingEvent;
import io.cucumber.java.ru.Когда;
import io.cucumber.java.ru.Тогда;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

public class OptionalFieldTypesSteps {

    private static final String LM_RESULTS_QUEUE = "результаты выпуска полисов ЛМ";
    private static final Set<String> CANCELLATION_BASE_FIELDS = Set.of("причина", "аннулировано");

    private final TaskMessages taskMessages;
    private final TaskQueue taskQueue;
    private final OneCMessages oneCMessages;
    private final StandQueues queues;
    private final PublishedMessages published;
    private final IssuanceRecords records;
    private final InboxJournal inbox;
    private final ServiceJournal journal;

    public OptionalFieldTypesSteps(TaskMessages taskMessages, TaskQueue taskQueue, OneCMessages oneCMessages,
            StandQueues queues, PublishedMessages published, IssuanceRecords records, InboxJournal inbox,
            ServiceJournal journal) {
        this.taskMessages = taskMessages;
        this.taskQueue = taskQueue;
        this.oneCMessages = oneCMessages;
        this.queues = queues;
        this.published = published;
        this.records = records;
        this.inbox = inbox;
        this.journal = journal;
    }

    @Когда("сервис Заявок публикует в свою очередь задачу на выпуск {string} по заявке {string} версии {int} с необязательными полями вида {string} для участников:")
    public void taskWithAbsentOptionalFields(String messageId, String requestId, int version, String kind,
            List<Map<String, String>> participants) {
        ObjectNode body = taskMessages.upserted(messageId, requestId, version, participants);
        OptionalJsonValues.applyAbsenceToTask((ObjectNode) body.get("data"), kind);
        publishTask(messageId, body);
    }

    @Когда("сервис Заявок публикует в свою очередь задачу на выпуск {string} по заявке {string} версии {int} с участниками и значением вида {string} в поле {string}:")
    public void taskWithFieldKind(String messageId, String requestId, int version, String kind, String field,
            List<Map<String, String>> participants) {
        ObjectNode body = taskMessages.upserted(messageId, requestId, version, participants);
        OptionalJsonValues.applyParticipantField((ObjectNode) body.get("data"), field, kind);
        publishTask(messageId, body);
    }

    @Когда("сервис Заявок публикует в свою очередь прекращение работы {string} по заявке {string} версии {int} с полем {string} вида {string}:")
    public void cancellationWithFieldKind(String messageId, String requestId, int version, String field, String kind,
            Map<String, String> fields) {
        StepTables.onlyKnown(fields, CANCELLATION_BASE_FIELDS);
        ObjectNode body = taskMessages.cancelled(messageId, requestId, version, fields);
        OptionalJsonValues.applyTopLevelField((ObjectNode) body.get("data"), field, "cancel_comment", kind);
        publishTask(messageId, body);
    }

    @Когда("1С:УСК публикует в свою очередь результат выпуска {string} по полису ЛМ участника {string} заявки {string} с полем {string} вида {string}:")
    public void lmPolicyResultWithFieldKind(String messageId, String participantId, String requestId, String field,
            String kind, Map<String, String> fields) {
        ObjectNode body = oneCMessages.issued(messageId, requestId, records.latestLmPolicyId(participantId, requestId),
                fields);
        OptionalJsonValues.applyTopLevelField((ObjectNode) body.get("data"), field, "policy_usk_number", kind);
        byte[] bytes = oneCMessages.bytes(body);
        published.remember(messageId, bytes);
        published.rememberQueued(LM_RESULTS_QUEUE, messageId);
        ((OneCQueue) queues.called(LM_RESULTS_QUEUE)).publish(bytes);
    }

    @Тогда("значение узла {string} сообщения {string} вида {string} есть в его теле, но не попало ни в причину исхода, ни в журнал Договоров")
    public void nodeValueStaysInBody(String field, String messageId, String kind) {
        InboxJournal.Entry entry = inbox.find(messageId)
                .orElseThrow(() -> new AssertionError("в журнале входящих нет записи о сообщении " + messageId));
        assertThat(entry.payload()).as("сохранённое тело сообщения %s", messageId).isNotNull();
        JsonNode stored = taskMessages.read(entry.payload().getBytes(StandardCharsets.UTF_8));
        String marker = OptionalJsonValues.verifyNodeAndMarker(stored, field, kind);
        assertThat(entry.errorMessage()).as("причина исхода").satisfiesAnyOf(
                reason -> assertThat(reason).isNull(),
                reason -> assertThat(reason).doesNotContain(marker));
        assertThat(journal.mentioning(marker)).extracting(ILoggingEvent::getFormattedMessage)
                .as("записи журнала с маркером узла %s", field).isEmpty();
    }

    private void publishTask(String messageId, ObjectNode body) {
        byte[] bytes = taskMessages.bytes(body);
        published.remember(messageId, bytes);
        published.rememberQueued(taskQueue.alias(), messageId);
        taskQueue.publish(bytes);
    }
}
