package ru.projectx.contracts.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import com.rabbitmq.client.GetResponse;
import io.cucumber.java.ru.Когда;
import io.cucumber.java.ru.Тогда;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.SoftAssertions;
import org.awaitility.Awaitility;
import ru.projectx.contracts.service.lm.request.LmPolicyRequestScheduler;
import tools.jackson.databind.JsonNode;

public class LmPolicyRequestSteps {

    private static final Duration PATIENCE = Duration.ofMillis(1500);
    private static final String ABSENT = "нет";

    private final LmPolicyRequestScheduler scheduler;
    private final StandQueues queues;
    private final ReceivedLmPolicyRequests received;
    private final RememberedMessageIds rememberedMessageIds;
    private final OutboxRecords journal;
    private final IssuanceRecords records;
    private final TaskMessages messages;

    public LmPolicyRequestSteps(LmPolicyRequestScheduler scheduler, StandQueues queues,
            ReceivedLmPolicyRequests received, RememberedMessageIds rememberedMessageIds, OutboxRecords journal,
            IssuanceRecords records, TaskMessages messages) {
        this.scheduler = scheduler;
        this.queues = queues;
        this.received = received;
        this.rememberedMessageIds = rememberedMessageIds;
        this.journal = journal;
        this.records = records;
        this.messages = messages;
    }

    @Когда("Договоры готовят запросы на выпуск полисов ЛМ")
    public void preparationPass() {
        scheduler.prepareDue();
    }

    @Тогда("1С:УСК получает из своей очереди {string} запросы на выпуск полисов ЛМ, всего за сценарий — {int}")
    public void receivedTotal(String queueName, int total) {
        OneCQueue source = (OneCQueue) queues.called(queueName);
        Awaitility.await().atMost(PATIENCE).untilAsserted(() -> {
            received.add(source.drain());
            assertThat(received.count()).isGreaterThanOrEqualTo(total);
        });
        received.add(source.drain());
        assertThat(received.count()).as("запросов на выпуск полисов ЛМ за сценарий").isEqualTo(total);
    }

    @Тогда("запрос на выпуск полиса ЛМ №{int} доставлен с delivery_mode {int}")
    public void deliveryMode(int ordinal, int deliveryMode) {
        assertThat(received.number(ordinal).getProps().getDeliveryMode()).isEqualTo(deliveryMode);
    }

    @Тогда("message_id запроса на выпуск полиса ЛМ №{int} совпадает с message_id исходящего сообщения №{int} по заявке {string}, присвоенным до публикации")
    public void messageIdMatchesTheRememberedOne(int receivedOrdinal, int ordinal, String requestId) {
        UUID remembered = rememberedMessageIds.of(ordinal, requestId);
        GetResponse message = received.number(receivedOrdinal);
        assertThat(message.getProps().getMessageId()).isEqualTo(remembered.toString());
        assertThat(journal.number(ordinal, requestId).messageId()).isEqualTo(remembered);
    }

    @Тогда("запрос на выпуск полиса ЛМ №{int} получен с конвертом:")
    public void envelope(int ordinal, Map<String, String> expected) {
        Envelopes.check(body(ordinal).path("meta"), expected);
    }

    @Тогда("запрос на выпуск полиса ЛМ №{int}: полис ЛМ участника {string} заявки {string}, поля data:")
    public void data(int ordinal, String participantId, String requestId, Map<String, String> expected) {
        JsonNode data = body(ordinal).path("data");
        assertThat(data.path("id").stringValue()).as("data.id")
                .isEqualTo(records.latestLmPolicyId(participantId, requestId).toString());
        SoftAssertions softly = new SoftAssertions();
        expected.forEach((path, value) -> field(softly, data, path, value));
        softly.assertAll();
    }

    private static void field(SoftAssertions softly, JsonNode data, String path, String expected) {
        JsonNode node = data;
        for (String segment : path.split("\\.")) {
            node = node.path(segment);
        }
        if (ABSENT.equals(expected)) {
            softly.assertThat(node.isMissingNode()).as("поля %s нет", path).isTrue();
        }
        else {
            softly.assertThat(node.asString()).as(path).isEqualTo(expected);
        }
    }

    private JsonNode body(int ordinal) {
        return messages.read(received.number(ordinal).getBody());
    }
}
