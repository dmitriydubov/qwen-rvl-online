package ru.projectx.contracts.acceptance;

import io.cucumber.spring.ScenarioScope;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
@ScenarioScope(proxyMode = ScopedProxyMode.NO)
public class OneCMessages {

    static final Map<String, String> ISSUED_EXAMPLE = Map.of(
            "status", "ISSUED",
            "policy_usk_id", "cb904f2a-7d61-4e08-89a5-40b1e3c7f012",
            "policy_usk_number", "LM-000012204",
            "policy.external_id", "5f0b2a77-8c31-4f2e-9d44-7c1a0b93e512",
            "policy.number", "0555123456",
            "policy.date_from", "2026-04-10",
            "policy.date_to", "2026-04-24",
            "documents[0].doc_type", "POLICY",
            "documents[0].file_url", "https://s3.sogaz.ru/rvl/lm/2026/02/0555123456.pdf",
            "documents[0].file_size", "96256");

    static final Map<String, String> REGISTERED_EXAMPLE = Map.ofEntries(
            Map.entry("status", "REGISTERED"),
            Map.entry("contract_usk_id", "a71f3c98-4e20-4a55-93b1-5d0c7e2f8814"),
            Map.entry("contract_usk_number", "DKM-000004417"),
            Map.entry("policy.external_id", "9e4d2c1b-7a35-4f68-b2c0-5d1e8f3a6b74"),
            Map.entry("policy.number", "0987654321"),
            Map.entry("policy.date_from", "2026-04-10"),
            Map.entry("policy.date_to", "2026-04-24"),
            Map.entry("documents[0].doc_type", "CONTRACT"),
            Map.entry("documents[0].file_url", "https://1c-usk.example/rvl/km/DKM-2026-000001.pdf"),
            Map.entry("documents[0].file_size", "205312"),
            Map.entry("documents[1].doc_type", "POLICY"),
            Map.entry("documents[1].file_url", "https://1c-usk.example/rvl/km/0987654321.pdf"),
            Map.entry("documents[1].file_size", "118784"));

    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    private static final String LM_TOPIC = "sogaz1c.lm-policy.issued";
    private static final String LM_EVENT = "LmPolicyIssued";
    private static final Pattern SEGMENT = Pattern.compile("([a-z_]+)(?:\\[(\\d+)])?");
    private static final Set<String> NUMERIC_FIELDS = Set.of("file_size");
    private static final String EMPTY_ARRAY = "[]";
    private static final String ABSENT = "нет";

    private final JsonMapper json;
    private final Clock clock;

    public OneCMessages(JsonMapper json, Clock clock) {
        this.json = json;
        this.clock = clock;
    }

    ObjectNode issued(String messageId, String requestId, UUID policyId, Map<String, String> fields) {
        return result(messageId, requestId, LM_TOPIC, LM_EVENT, policyId, fields);
    }

    ObjectNode failed(String messageId, String requestId, UUID policyId, String errorCode, String errorMessage) {
        return result(messageId, requestId, LM_TOPIC, LM_EVENT, policyId,
                Map.of("status", "ERROR", "error_code", errorCode, "error_message", errorMessage));
    }

    ObjectNode registered(String messageId, String requestId, UUID contractId, Map<String, String> fields) {
        return result(messageId, requestId, "sogaz1c.km-contract.registered", "KmContractRegistered", contractId, fields);
    }

    private ObjectNode result(String messageId, String requestId, String topic, String eventType, UUID recordId,
            Map<String, String> fields) {
        ObjectNode root = json.createObjectNode();
        root.putObject("meta")
                .put("message_id", messageId)
                .put("correlation_id", requestId)
                .put("topic", topic)
                .put("event_type", eventType)
                .put("schema_version", "1.0")
                .put("created_at", OffsetDateTime.now(clock.withZone(MOSCOW)).toString())
                .put("entity_version", 1)
                .put("source", "1C-USK");
        ObjectNode data = root.putObject("data").put("id", recordId.toString());
        fields.forEach((path, value) -> put(data, path, value));
        return root;
    }

    ObjectNode defective(String messageId, String requestId, UUID policyId, String defect) {
        ObjectNode root = issued(messageId, requestId, policyId, ISSUED_EXAMPLE);
        ObjectNode data = (ObjectNode) root.get("data");
        switch (defect) {
            case "нет поля data.policy при status ISSUED" -> data.remove("policy");
            case "нет поля data.policy_usk_id при status ISSUED" -> data.remove("policy_usk_id");
            case "нет поля data.error_code при status ERROR" -> data.put("status", "ERROR")
                    .put("error_message", "Стенд: ошибка без кода");
            case "status FAILED" -> data.put("status", "FAILED");
            default -> throw new IllegalArgumentException("неизвестный дефект результата выпуска: " + defect);
        }
        return root;
    }

    ObjectNode defectiveRegistration(String messageId, String requestId, UUID contractId, String defect) {
        ObjectNode root = registered(messageId, requestId, contractId, REGISTERED_EXAMPLE);
        ObjectNode data = (ObjectNode) root.get("data");
        switch (defect) {
            case "нет поля data.contract_usk_id при status REGISTERED" -> data.remove("contract_usk_id");
            case "нет поля data.contract_usk_number при status REGISTERED" -> data.remove("contract_usk_number");
            case "нет поля data.policy при status REGISTERED" -> data.remove("policy");
            case "нет поля data.documents при status REGISTERED" -> data.remove("documents");
            case "нет поля data.error_code при status ERROR" -> data.put("status", "ERROR")
                    .put("error_message", "Стенд: ошибка без кода");
            case "status FAILED" -> data.put("status", "FAILED");
            case "doc_type MEDICAL_CERTIFICATE у documents[0]" -> put(data, "documents[0].doc_type", "MEDICAL_CERTIFICATE");
            case "doc_type INVOICE у documents[0]" -> put(data, "documents[0].doc_type", "INVOICE");
            default -> throw new IllegalArgumentException("неизвестный дефект результата регистрации: " + defect);
        }
        return root;
    }

    byte[] bytes(ObjectNode body) {
        return json.writeValueAsBytes(body);
    }

    private static void put(ObjectNode data, String path, String value) {
        List<String> segments = Arrays.asList(path.split("\\."));
        ObjectNode container = data;
        for (String segment : segments.subList(0, segments.size() - 1)) {
            container = child(container, segment);
        }
        leaf(container, segments.get(segments.size() - 1), value);
    }

    private static ObjectNode child(ObjectNode parent, String segment) {
        Matcher matcher = SEGMENT.matcher(segment);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("непонятный путь поля: " + segment);
        }
        String name = matcher.group(1);
        if (matcher.group(2) == null) {
            return parent.has(name) ? (ObjectNode) parent.get(name) : parent.putObject(name);
        }
        ArrayNode array = parent.has(name) ? (ArrayNode) parent.get(name) : parent.putArray(name);
        int index = Integer.parseInt(matcher.group(2));
        while (array.size() <= index) {
            array.addObject();
        }
        return (ObjectNode) array.get(index);
    }

    private static void leaf(ObjectNode container, String name, String value) {
        if (ABSENT.equals(value)) {
            container.remove(name);
        }
        else if (EMPTY_ARRAY.equals(value)) {
            container.putArray(name);
        }
        else if (NUMERIC_FIELDS.contains(name)) {
            container.put(name, Long.parseLong(value));
        }
        else {
            container.put(name, value);
        }
    }
}
