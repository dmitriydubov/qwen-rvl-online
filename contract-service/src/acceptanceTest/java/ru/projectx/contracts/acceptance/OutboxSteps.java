package ru.projectx.contracts.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import com.rabbitmq.client.GetResponse;
import io.cucumber.java.ru.Дано;
import io.cucumber.java.ru.Когда;
import io.cucumber.java.ru.Тогда;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.awaitility.Awaitility;
import ru.projectx.contracts.service.outbox.OutboxPublisher;

public class OutboxSteps {

    private static final Duration PATIENCE = Duration.ofMillis(1500);

    private final OutboxRecords journal;
    private final StatusQueue queue;
    private final StandQueues queues;
    private final ReceivedStatusMessages received;
    private final OutboxPublisher publisher;
    private final ErasureStates erasureStates;
    private final RememberedMessageIds rememberedMessageIds;

    public OutboxSteps(OutboxRecords journal, StatusQueue queue, StandQueues queues, ReceivedStatusMessages received,
            OutboxPublisher publisher, ErasureStates erasureStates, RememberedMessageIds rememberedMessageIds) {
        this.journal = journal;
        this.queue = queue;
        this.queues = queues;
        this.received = received;
        this.publisher = publisher;
        this.erasureStates = erasureStates;
        this.rememberedMessageIds = rememberedMessageIds;
    }

    @Когда("Договоры публикуют исходящие сообщения")
    public void publicationPass() {
        publisher.publishDue();
    }

    @Тогда("исходы исходящих сообщений по заявке {string} в порядке создания — {string}")
    public void outcomesInOrder(String requestId, String outcomes) {
        assertThat(journal.ofRequest(requestId)).extracting(OutboxRecords.Entry::outcome)
                .containsExactlyElementsOf(Arrays.stream(outcomes.split(",")).map(String::trim).toList());
    }

    @Тогда("в журнале исходящих нет сообщений по заявке {string}")
    public void noRecords(String requestId) {
        assertThat(journal.ofRequest(requestId)).isEmpty();
    }

    @Тогда("у исходящего сообщения №{int} по заявке {string} есть message_id")
    public void hasMessageId(int ordinal, String requestId) {
        UUID messageId = journal.number(ordinal, requestId).messageId();
        assertThat(messageId).isNotNull();
        rememberedMessageIds.remember(ordinal, requestId, messageId);
    }

    @Тогда("message_id исходящего сообщения №{int} по заявке {string} отличается от message_id исходящего сообщения №{int} по заявке {string}")
    public void messageIdsDiffer(int ordinal, String requestId, int otherOrdinal, String otherRequestId) {
        assertThat(journal.number(ordinal, requestId).messageId())
                .isNotEqualTo(journal.number(otherOrdinal, otherRequestId).messageId());
    }

    @Тогда("тип события исходящего сообщения №{int} по заявке {string} — {string}")
    public void eventType(int ordinal, String requestId, String eventType) {
        assertThat(journal.number(ordinal, requestId).eventType()).isEqualTo(eventType);
    }

    @Тогда("message_id сообщения о состоянии выпуска №{int} совпадает с message_id исходящего сообщения №{int} по заявке {string}, присвоенным до публикации")
    public void receivedMessageIdMatchesTheRememberedOne(int receivedOrdinal, int ordinal, String requestId) {
        UUID remembered = rememberedMessageIds.of(ordinal, requestId);
        GetResponse message = received.number(receivedOrdinal);
        assertThat(message.getProps().getMessageId()).isEqualTo(remembered.toString());
        assertThat(journal.number(ordinal, requestId).messageId()).isEqualTo(remembered);
    }

    @Тогда("исходящее сообщение №{int} по заявке {string} опубликовано")
    public void published(int ordinal, String requestId) {
        assertThat(journal.number(ordinal, requestId).outcome()).isEqualTo("опубликовано");
    }

    @Тогда("исходящее сообщение №{int} по заявке {string} отклонено окончательно после попытки {int} с причиной {string}")
    public void rejected(int ordinal, String requestId, int attempt, String reason) {
        OutboxRecords.Entry entry = journal.number(ordinal, requestId);
        assertThat(entry.outcome()).isEqualTo("отклонено окончательно");
        assertThat(entry.attempts()).isEqualTo(attempt);
        assertThat(entry.errorMessage()).isEqualTo(reason);
    }

    @Тогда("тело исходящего сообщения №{int} по заявке {string} стёрто")
    public void payloadErased(int ordinal, String requestId) {
        assertThat(journal.number(ordinal, requestId).payload()).as("тело исходящего сообщения №%s", ordinal).isNull();
    }

    @Тогда("тело исходящего сообщения №{int} по заявке {string} сохранено")
    public void payloadKept(int ordinal, String requestId) {
        assertThat(journal.number(ordinal, requestId).payload()).as("тело исходящего сообщения №%s", ordinal).isNotNull();
    }

    @Тогда("статус и число попыток исходящего сообщения №{int} по заявке {string} те же, что до прохода стирания")
    public void stateUnchangedByErasure(int ordinal, String requestId) {
        OutboxRecords.Entry entry = journal.number(ordinal, requestId);
        assertThat(erasureStates.unchanged(entry.messageId(), entry.status(), entry.attempts()))
                .as("статус и попытки записи %s до и после стирания", entry.messageId()).isTrue();
    }

    @Дано("очередь {string} удалена у брокера")
    public void queueDeleted(String queueName) {
        queue.called(queueName).delete();
    }

    @Когда("очередь {string} объявлена заново")
    public void queueRedeclared(String queueName) {
        queue.called(queueName).declare();
    }

    @Тогда("очередь {string} пуста")
    public void queueEmpty(String queueName) {
        StandQueue target = queues.called(queueName);
        assertThat(target.state().getMessageCount()).as("сообщений в очереди %s", target.name()).isZero();
    }

    @Тогда("сервис Заявок получает из очереди {string} сообщения о состоянии выпуска, всего за сценарий — {int}")
    public void receivedTotal(String queueName, int total) {
        StatusQueue source = queue.called(queueName);
        Awaitility.await().atMost(PATIENCE).untilAsserted(() -> {
            received.add(source.drain());
            assertThat(received.count()).isGreaterThanOrEqualTo(total);
        });
        received.add(source.drain());
        assertThat(received.count()).as("сообщений о состоянии выпуска за сценарий").isEqualTo(total);
    }

    @Тогда("сообщение о состоянии выпуска №{int} доставлено с delivery_mode {int}")
    public void deliveryMode(int ordinal, int deliveryMode) {
        assertThat(received.number(ordinal).getProps().getDeliveryMode()).isEqualTo(deliveryMode);
    }

    @Тогда("сообщения о состоянии выпуска получены по заявкам в порядке — {string}")
    public void receivedRequestsInOrder(String requestIds) {
        List<String> expected = Arrays.stream(requestIds.split(",")).map(String::trim).toList();
        assertThat(received.all()).extracting(message -> message.getProps().getCorrelationId())
                .containsExactlyElementsOf(expected);
    }
}
