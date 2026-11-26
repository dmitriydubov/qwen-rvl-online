package ru.projectx.contracts.support;

import java.nio.charset.StandardCharsets;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

public final class LmPolicyResultBodies {

    public static final String MESSAGE_ID = "5b1e7d44-2c09-4a63-8f17-6e0b9a3c1d05";
    public static final String REQUEST_ID = "61a7982c-a0b4-4bbf-9527-60b40b752803";
    public static final String POLICY_ID = "8e2d0b17-5a44-4c90-b2e3-71f0c6a45d39";
    public static final String POLICY_USK_ID = "cb904f2a-7d61-4e08-89a5-40b1e3c7f012";
    public static final String POLICY_EXTERNAL_ID = "5f0b2a77-8c31-4f2e-9d44-7c1a0b93e512";
    public static final String FILE_URL = "https://s3.sogaz.ru/rvl/lm/2026/02/0555123456.pdf";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ObjectNode root;

    private LmPolicyResultBodies(ObjectNode root) {
        this.root = root;
    }

    public static LmPolicyResultBodies issued() {
        ObjectNode root = JSON.createObjectNode();
        meta(root);
        ObjectNode data = root.putObject("data")
                .put("id", POLICY_ID)
                .put("policy_usk_id", POLICY_USK_ID)
                .put("policy_usk_number", "LM-000012204")
                .put("status", "ISSUED");
        data.putObject("policy")
                .put("external_id", POLICY_EXTERNAL_ID)
                .put("number", "0555123456")
                .put("date_from", "2026-04-10")
                .put("date_to", "2026-04-24");
        data.putArray("documents").addObject()
                .put("doc_type", "POLICY")
                .put("file_url", FILE_URL)
                .put("file_size", 96256);
        data.putNull("error_code").putNull("error_message");
        return new LmPolicyResultBodies(root);
    }

    public static LmPolicyResultBodies failed() {
        ObjectNode root = JSON.createObjectNode();
        meta(root);
        root.putObject("data")
                .put("id", POLICY_ID)
                .put("status", "ERROR")
                .put("error_code", "INSURED_NOT_COVERED")
                .put("error_message", "Стенд: застрахованный не входит в корпоративный договор");
        return new LmPolicyResultBodies(root);
    }

    private static void meta(ObjectNode root) {
        root.putObject("meta")
                .put("message_id", MESSAGE_ID)
                .put("correlation_id", REQUEST_ID)
                .put("topic", "sogaz1c.lm-policy.issued")
                .put("event_type", "LmPolicyIssued")
                .put("schema_version", "1.0")
                .put("created_at", "2026-02-11T09:15:00+03:00")
                .put("entity_version", 1)
                .put("source", "1C-USK");
    }

    public LmPolicyResultBodies withDataField(String name, String value) {
        data().put(name, value);
        return this;
    }

    public LmPolicyResultBodies withDataJson(String name, String jsonValue) {
        data().set(name, JSON.readTree(jsonValue));
        return this;
    }

    public LmPolicyResultBodies withoutDataField(String name) {
        data().remove(name);
        return this;
    }

    public LmPolicyResultBodies withoutPolicyField(String name) {
        ((ObjectNode) data().get("policy")).remove(name);
        return this;
    }

    public LmPolicyResultBodies withDocumentField(int index, String name, String value) {
        ((ObjectNode) data().get("documents").get(index)).put(name, value);
        return this;
    }

    public LmPolicyResultBodies withoutDocumentField(int index, String name) {
        ((ObjectNode) data().get("documents").get(index)).remove(name);
        return this;
    }

    public LmPolicyResultBodies withEmptyDocuments() {
        data().putArray("documents");
        return this;
    }

    private ObjectNode data() {
        return (ObjectNode) root.get("data");
    }

    public String toJson() {
        return JSON.writeValueAsString(root);
    }

    public byte[] toBytes() {
        return toJson().getBytes(StandardCharsets.UTF_8);
    }
}
