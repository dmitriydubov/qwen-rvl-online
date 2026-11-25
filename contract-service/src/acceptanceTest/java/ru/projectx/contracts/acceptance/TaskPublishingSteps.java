package ru.projectx.contracts.acceptance;

import io.cucumber.java.DataTableType;
import io.cucumber.java.ru.Дано;
import io.cucumber.java.ru.Когда;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

public class TaskPublishingSteps {

    private static final List<Map<String, String>> DEFAULT_PARTICIPANTS = List.of(
            Map.of("участник", "ae8312c3-350b-4d0b-806c-5e2460e74dbc", "родственник", "нет", "доплата", "0.00"),
            Map.of("участник", "9275471c-d2fe-484f-97a6-d6d7b1267b5e", "родственник", "да", "доплата", "34000.00",
                    "родство", "SPOUSE"));

    private final TaskMessages messages;
    private final TaskQueue queue;
    private final PublishedMessages published;
    private final RequestFixture requests;

    public TaskPublishingSteps(TaskMessages messages, TaskQueue queue, PublishedMessages published,
            RequestFixture requests) {
        this.messages = messages;
        this.queue = queue;
        this.published = published;
        this.requests = requests;
    }

    @DataTableType
    public Map<String, String> row(Map<String, String> row) {
        return row;
    }

    @Дано("у сервиса Заявок есть заявка:")
    public void requestDefined(Map<String, String> requisites) {
        requests.define(requisites);
    }

    @Дано("сервис Заявок публикует в свою очередь задачу на выпуск {string} по заявке {string} версии {int} с участниками:")
    public void taskPublished(String messageId, String requestId, int version, List<Map<String, String>> participants) {
        publish(messageId, messages.upserted(messageId, requestId, version, participants));
    }

    @Когда("сервис Заявок публикует в свою очередь задачу на выпуск {string} по заявке {string} версии {int} с дефектом {string}")
    public void defectiveTaskPublished(String messageId, String requestId, int version, String defect) {
        ObjectNode body = messages.upserted(messageId, requestId, version, DEFAULT_PARTICIPANTS);
        ObjectNode data = (ObjectNode) body.get("data");
        switch (defect) {
            case "нет поля data.date_from" -> data.remove("date_from");
            case "в data.participants нет ни одного участника" -> data.putArray("participants");
            case "amount_due участника отрицательный" -> participant(data, 0).put("amount_due", -1.00);
            case "два участника без признака родственника" -> participant(data, 1).put("is_relative", false);
            case "нет участника без признака родственника" -> participant(data, 0).put("is_relative", true);
            default -> eventType(body, defect);
        }
        publish(messageId, body);
    }

    @Когда("сервис Заявок публикует в очередь {string} сообщение с дефектом конверта {string}")
    public void defectiveEnvelopePublished(String queueName, String defect) {
        ObjectNode body = messages.upserted("b81f0f3a-1c44-4f2a-9a1e-5a0e2f7c9d11",
                "61a7982c-a0b4-4bbf-9527-60b40b752803", 1, DEFAULT_PARTICIPANTS);
        ObjectNode meta = (ObjectNode) body.get("meta");
        switch (defect) {
            case "нет meta.message_id" -> meta.remove("message_id");
            case "meta.message_id не GUID" -> meta.put("message_id", "1-2-3-4-5");
            case "meta.correlation_id не GUID" -> meta.put("correlation_id", "1-2-3-4-5");
            case "meta.entity_version не число" -> meta.put("entity_version", "три");
            case "тело не JSON", "нулевой символ в теле", "непарный суррогат в теле" -> { }
            default -> throw new IllegalArgumentException("неизвестный дефект конверта: " + defect);
        }
        byte[] bytes = defectiveBytes(defect, new String(messages.bytes(body), StandardCharsets.UTF_8));
        published.rememberDefectiveEnvelope(bytes);
        published.rememberQueued(queueName, "b81f0f3a-1c44-4f2a-9a1e-5a0e2f7c9d11");
        published.rememberDefectiveEnvelopeSource(messages.bytes(messages.upserted(
                "b81f0f3a-1c44-4f2a-9a1e-5a0e2f7c9d11", "61a7982c-a0b4-4bbf-9527-60b40b752803", 1,
                DEFAULT_PARTICIPANTS)));
        queue.called(queueName).publish(bytes);
    }

    @Когда("сервис Заявок публикует в свою очередь задачу на выпуск {string} по заявке {string} версии {int} с узлом вида {string} в необязательном поле {string} участника {int} и участниками:")
    public void taskPublishedWithFieldNode(String messageId, String requestId, int version, String kind, String field,
            int index, List<Map<String, String>> participants) {
        ObjectNode body = messages.upserted(messageId, requestId, version, participants);
        FieldNodes.put(participant((ObjectNode) body.get("data"), index), field, kind, "Секретович");
        publish(messageId, body);
    }

    @Когда("сервис Заявок публикует в свою очередь прекращение работы {string} по заявке {string} версии {int} с узлом вида {string} в поле cancel_comment")
    public void cancellationPublishedWithCommentNode(String messageId, String requestId, int version, String kind) {
        ObjectNode body = messages.cancelled(messageId, requestId, version,
                Map.of("аннулировано", "2026-02-12T14:30:00+03:00", "причина", "INSURED_REFUSED"));
        FieldNodes.put((ObjectNode) body.get("data"), "cancel_comment", kind, "Стенд: комментарий");
        publish(messageId, body);
    }

    @Дано("сервис Заявок публикует в свою очередь прекращение работы {string} по заявке {string} версии {int}:")
    public void cancellationPublished(String messageId, String requestId, int version, Map<String, String> fields) {
        publish(messageId, messages.cancelled(messageId, requestId, version, fields));
    }

    private static byte[] defectiveBytes(String defect, String json) {
        String text = switch (defect) {
            case "тело не JSON" -> "<не JSON> " + json;
            case "нулевой символ в теле" -> json.replace("Тестовый адрес, 1", "Тестовый адрес, 1 \\u0000");
            case "непарный суррогат в теле" -> json.replace("Тестовый адрес, 1", "Тестовый адрес, 1 \\ud800");
            default -> json;
        };
        if (text.equals(json) && defect.endsWith(" в теле")) {
            throw new IllegalStateException("дефект " + defect + " не внесён: в теле нет адреса участника 0");
        }
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private void publish(String messageId, ObjectNode body) {
        byte[] bytes = messages.bytes(body);
        published.remember(messageId, bytes);
        published.rememberQueued(queue.alias(), messageId);
        queue.publish(bytes);
    }

    private static ObjectNode participant(ObjectNode data, int index) {
        return (ObjectNode) ((ArrayNode) data.get("participants")).get(index);
    }

    private static void eventType(ObjectNode body, String defect) {
        if (!defect.startsWith("event_type ")) {
            throw new IllegalArgumentException("неизвестный дефект задачи: " + defect);
        }
        ((ObjectNode) body.get("meta")).put("event_type", defect.substring("event_type ".length()));
    }
}
