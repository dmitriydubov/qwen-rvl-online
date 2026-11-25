package ru.projectx.contracts.support;

import java.nio.charset.StandardCharsets;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

public final class TaskBodies {

    public static final String MESSAGE_ID = "b81f0f3a-1c44-4f2a-9a1e-5a0e2f7c9d11";
    public static final String REQUEST_ID = "61a7982c-a0b4-4bbf-9527-60b40b752803";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ObjectNode root;

    private TaskBodies(ObjectNode root) {
        this.root = root;
    }

    public static TaskBodies upserted() {
        ObjectNode root = JSON.createObjectNode();
        meta(root, "rvl.contract-task.upserted", "ContractTaskUpserted", 1);
        ObjectNode data = root.putObject("data");
        data.put("individual_request_id", REQUEST_ID)
                .put("individual_request_number", "IZ-2026-004512")
                .put("group_request_id", "c9bfd8fc-7ed2-4fcc-a67f-b8afe814b36f")
                .put("insurance_contract_id", "e12aab1a-1582-4b3a-af36-d30e637953f1")
                .put("insurer_policy_id", "a23ba061-f742-4b20-8b95-36a0e525c087")
                .put("lpu_external_id", "1b1b4fdf-bd2a-4055-a127-8c24c544a23f")
                .put("date_from", "2026-04-10")
                .put("date_to", "2026-04-24");
        ArrayNode participants = data.putArray("participants");
        participant(participants.addObject(), "ae8312c3-350b-4d0b-806c-5e2460e74dbc", false, "0.00", null)
                .put("last_name", "Тестов").put("first_name", "Тест").put("middle_name", "Тестович")
                .put("birth_date", "1984-03-11").put("gender", "MALE")
                .set("document", document("RF_PASSPORT", "4510", "123456", "2010-04-20"));
        participant(participants.addObject(), "9275471c-d2fe-484f-97a6-d6d7b1267b5e", true, "34000.00", "SPOUSE")
                .put("last_name", "Тестова").put("first_name", "Теста").put("middle_name", "Тестовна")
                .put("birth_date", "1988-07-02").put("gender", "FEMALE")
                .set("document", document("RF_PASSPORT", "4512", "654321", "2012-08-15"));
        return new TaskBodies(root);
    }

    public static TaskBodies cancelled(String reason) {
        ObjectNode root = JSON.createObjectNode();
        meta(root, "rvl.contract-task.cancelled", "ContractTaskCancelled", 2);
        root.putObject("data")
                .put("individual_request_id", REQUEST_ID)
                .put("individual_request_number", "IZ-2026-004512")
                .put("cancelled_at", "2026-02-12T14:30:00+03:00")
                .put("cancel_reason", reason)
                .put("cancel_comment", "Стенд: комментарий");
        return new TaskBodies(root);
    }

    private static void meta(ObjectNode root, String topic, String eventType, int version) {
        root.putObject("meta")
                .put("message_id", MESSAGE_ID)
                .put("correlation_id", REQUEST_ID)
                .put("topic", topic)
                .put("event_type", eventType)
                .put("schema_version", "1.0")
                .put("created_at", "2026-02-10T10:05:00+03:00")
                .put("entity_version", version)
                .put("source", "RVL-REQUESTS");
    }

    private static ObjectNode participant(ObjectNode node, String id, boolean relative, String amountDue,
            String relationType) {
        node.put("id", id).put("is_relative", relative)
                .put("policy_id", relative ? null : "a23ba061-f742-4b20-8b95-36a0e525c087")
                .put("service_external_id", "bf3ef3a5-fa45-4c8e-ab91-3432399fe5d5")
                .put("amount_total", 168000.00).put("limit_amount", 150000.00).put("copay_percent", 0)
                .put("amount_due", new java.math.BigDecimal(amountDue));
        ObjectNode person = node.putObject("personal_data");
        if (relationType != null) {
            person.put("relation_type", relationType);
        }
        return person.put("address", "Тестовый адрес");
    }

    private static ObjectNode document(String type, String series, String number, String issuedAt) {
        return JSON.createObjectNode().put("type", type).put("series", series).put("number", number)
                .put("issued_by", "Тестовый орган").put("issued_at", issuedAt).put("division_code", "770-001");
    }

    public TaskBodies withMetaField(String name, String value) {
        ((ObjectNode) root.get("meta")).put(name, value);
        return this;
    }

    public TaskBodies withoutMetaField(String name) {
        ((ObjectNode) root.get("meta")).remove(name);
        return this;
    }

    public TaskBodies withDataField(String name, String value) {
        ((ObjectNode) root.get("data")).put(name, value);
        return this;
    }

    public TaskBodies withoutDataField(String name) {
        ((ObjectNode) root.get("data")).remove(name);
        return this;
    }

    public TaskBodies withoutParticipants() {
        ((ObjectNode) root.get("data")).putArray("participants");
        return this;
    }

    public TaskBodies withAmountDue(int index, String amountDue) {
        participant(index).put("amount_due", new java.math.BigDecimal(amountDue));
        return this;
    }

    public TaskBodies withRelative(int index, boolean relative) {
        participant(index).put("is_relative", relative);
        return this;
    }

    public TaskBodies withParticipantField(int index, String name, String value) {
        participant(index).put(name, value);
        return this;
    }

    public TaskBodies withDataNode(String name, JsonNode value) {
        ((ObjectNode) root.get("data")).set(name, value);
        return this;
    }

    public TaskBodies withParticipantNode(int index, String path, JsonNode value) {
        String[] names = path.split("\\.");
        ObjectNode node = participant(index);
        for (int i = 0; i < names.length - 1; i++) {
            node = (ObjectNode) node.get(names[i]);
        }
        node.set(names[names.length - 1], value);
        return this;
    }

    public TaskBodies withoutParticipantField(int index, String path) {
        String[] names = path.split("\\.");
        ObjectNode node = participant(index);
        for (int i = 0; i < names.length - 1; i++) {
            node = (ObjectNode) node.get(names[i]);
        }
        node.remove(names[names.length - 1]);
        return this;
    }

    private ObjectNode participant(int index) {
        return (ObjectNode) root.get("data").get("participants").get(index);
    }

    public String toJson() {
        return JSON.writeValueAsString(root);
    }

    public byte[] toBytes() {
        return toJson().getBytes(StandardCharsets.UTF_8);
    }
}
