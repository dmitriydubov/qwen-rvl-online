package ru.projectx.contracts.acceptance;

import io.cucumber.java.ru.Когда;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.node.ObjectNode;

public class OneCPublishingSteps {

    private static final String RESULTS = "результаты выпуска полисов ЛМ";
    private static final String REGISTRATIONS = "результаты регистрации договоров КМ";

    private final OneCMessages messages;
    private final StandQueues queues;
    private final PublishedMessages published;
    private final IssuanceRecords records;

    public OneCPublishingSteps(OneCMessages messages, StandQueues queues, PublishedMessages published,
            IssuanceRecords records) {
        this.messages = messages;
        this.queues = queues;
        this.published = published;
        this.records = records;
    }

    @Когда("1С:УСК публикует в свою очередь результат выпуска {string} по полису ЛМ участника {string} заявки {string}:")
    public void resultPublished(String messageId, String participantId, String requestId, Map<String, String> fields) {
        publish(messageId, messages.issued(messageId, requestId, records.latestLmPolicyId(participantId, requestId),
                fields));
    }

    @Когда("1С:УСК публикует в свою очередь результат выпуска {string} по заявке {string} для полиса ЛМ с идентификатором {string}:")
    public void resultPublishedForPolicy(String messageId, String requestId, String policyId,
            Map<String, String> fields) {
        publish(messageId, messages.issued(messageId, requestId, UUID.fromString(policyId), fields));
    }

    @Когда("1С:УСК публикует в свою очередь результат выпуска {string} по полису ЛМ участника {string} заявки {string} с узлом вида {string} в поле policy_usk_number")
    public void resultPublishedWithNumberNode(String messageId, String participantId, String requestId, String kind) {
        ObjectNode body = messages.issued(messageId, requestId, records.latestLmPolicyId(participantId, requestId),
                OneCMessages.ISSUED_EXAMPLE);
        FieldNodes.put((ObjectNode) body.get("data"), "policy_usk_number", kind, "LM-000012204");
        publish(messageId, body);
    }

    @Когда("1С:УСК публикует в свою очередь результат выпуска {string} по полису ЛМ участника {string} заявки {string} с ошибкой {string}, текстом {string} и узлом вида {string} в поле policy_usk_number")
    public void failedResultPublishedWithNumberNode(String messageId, String participantId, String requestId,
            String errorCode, String errorMessage, String kind) {
        ObjectNode body = messages.failed(messageId, requestId, records.latestLmPolicyId(participantId, requestId),
                errorCode, errorMessage);
        FieldNodes.put((ObjectNode) body.get("data"), "policy_usk_number", kind, "LM-000012204");
        publish(messageId, body);
    }

    @Когда("1С:УСК публикует в свою очередь результат выпуска {string} по полису ЛМ участника {string} заявки {string} с дефектом {string}")
    public void defectiveResultPublished(String messageId, String participantId, String requestId, String defect) {
        publish(messageId, messages.defective(messageId, requestId,
                records.latestLmPolicyId(participantId, requestId), defect));
    }

    @Когда("1С:УСК публикует в свою очередь результат регистрации {string} по договору КМ участника {string} заявки {string}:")
    public void registrationPublished(String messageId, String participantId, String requestId,
            Map<String, String> fields) {
        publish(REGISTRATIONS, messageId, messages.registered(messageId, requestId,
                records.latestContractId(participantId, requestId), fields));
    }

    @Когда("1С:УСК публикует в свою очередь результат регистрации {string} по заявке {string} для договора КМ с идентификатором {string}:")
    public void registrationPublishedForContract(String messageId, String requestId, String contractId,
            Map<String, String> fields) {
        publish(REGISTRATIONS, messageId, messages.registered(messageId, requestId, UUID.fromString(contractId), fields));
    }

    @Когда("1С:УСК публикует в свою очередь результат регистрации {string} по договору КМ участника {string} заявки {string} с дефектом {string}")
    public void defectiveRegistrationPublished(String messageId, String participantId, String requestId, String defect) {
        publish(REGISTRATIONS, messageId, messages.defectiveRegistration(messageId, requestId,
                records.latestContractId(participantId, requestId), defect));
    }

    private void publish(String messageId, ObjectNode body) {
        publish(RESULTS, messageId, body);
    }

    private void publish(String queue, String messageId, ObjectNode body) {
        byte[] bytes = messages.bytes(body);
        published.remember(messageId, bytes);
        published.rememberQueued(queue, messageId);
        ((OneCQueue) queues.called(queue)).publish(bytes);
    }
}
