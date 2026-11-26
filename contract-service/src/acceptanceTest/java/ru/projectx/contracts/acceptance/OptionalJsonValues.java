package ru.projectx.contracts.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

final class OptionalJsonValues {

    private static final Pattern PARTICIPANT_FIELD = Pattern.compile("participants\\[(\\d+)]\\.(.+)");
    private static final Set<String> OPTIONAL_PARTICIPANT_FIELDS = Set.of(
            "program_id",
            "personal_data.middle_name",
            "personal_data.relation_type",
            "personal_data.address",
            "personal_data.document.series",
            "personal_data.document.issued_by",
            "personal_data.document.issued_at",
            "personal_data.document.division_code");
    private static final List<String> PERSONAL_OPTIONAL_FIELDS = List.of(
            "personal_data.middle_name",
            "personal_data.address",
            "personal_data.document.series",
            "personal_data.document.issued_by",
            "personal_data.document.issued_at",
            "personal_data.document.division_code");
    private static final List<String> NONE_EXTRA_OPTIONAL_FIELDS = List.of(
            "program_id",
            "personal_data.relation_type");
    private static final String ABSENT_KEY = "нет ключа";
    private static final String EXPLICIT_NULL = "null";
    private static final String NUMBER = "число";
    private static final String BOOLEAN = "логическое";
    private static final String ARRAY = "массив";
    private static final String OBJECT = "объект";
    private static final String EMPTY = "пустая строка";
    private static final String BLANK = "пробелы";
    private static final int NUMBER_VALUE = 987654;
    private static final String NUMBER_MARKER = "987654";
    private static final String BOOLEAN_MARKER = "true";
    private static final String NODE_MARKER = "Тестов-Узел";

    private OptionalJsonValues() {
    }

    static void applyAbsenceToTask(ObjectNode data, String kind) {
        if (!ABSENT_KEY.equals(kind) && !EXPLICIT_NULL.equals(kind)) {
            throw new IllegalArgumentException("неизвестный вид отсутствия: " + kind);
        }
        ArrayNode participants = (ArrayNode) data.get("participants");
        for (int index = 0; index < participants.size(); index++) {
            ObjectNode participant = (ObjectNode) participants.get(index);
            for (String path : PERSONAL_OPTIONAL_FIELDS) {
                applyPath(participant, path, kind);
            }
            if (!participant.path("is_relative").booleanValue()
                    && participant.path("amount_due").decimalValue().signum() == 0) {
                for (String path : NONE_EXTRA_OPTIONAL_FIELDS) {
                    applyPath(participant, path, kind);
                }
            }
        }
    }

    static void applyParticipantField(ObjectNode data, String path, String kind) {
        Matcher matcher = participantMatcher(path);
        int index = Integer.parseInt(matcher.group(1));
        String relative = matcher.group(2);
        ArrayNode participants = (ArrayNode) data.get("participants");
        if (index < 0 || index >= participants.size()) {
            throw new IllegalArgumentException("недопустимый индекс участника в пути: " + path);
        }
        applyPath((ObjectNode) participants.get(index), relative, kind);
    }

    static void applyTopLevelField(ObjectNode data, String field, String expectedField, String kind) {
        if (!expectedField.equals(field)) {
            throw new IllegalArgumentException("неизвестное поле сообщения: " + field);
        }
        applyValue(data, field, kind);
    }

    static String verifyNodeAndMarker(JsonNode root, String path, String kind) {
        JsonNode node = nodeAt(root.path("data"), path);
        return switch (kind) {
            case NUMBER -> {
                assertThat(node.isIntegralNumber() && node.longValue() == NUMBER_VALUE)
                        .as("узел %s — число %s", path, NUMBER_MARKER).isTrue();
                yield NUMBER_MARKER;
            }
            case BOOLEAN -> {
                assertThat(node.isBoolean() && node.booleanValue())
                        .as("узел %s — логическое %s", path, BOOLEAN_MARKER).isTrue();
                yield BOOLEAN_MARKER;
            }
            case ARRAY -> {
                assertThat(node.isArray() && node.size() == 1
                        && node.path(0).isString() && NODE_MARKER.equals(node.path(0).stringValue()))
                        .as("узел %s — массив с %s", path, NODE_MARKER).isTrue();
                yield NODE_MARKER;
            }
            case OBJECT -> {
                assertThat(node.isObject()
                        && node.path("note").isString() && NODE_MARKER.equals(node.path("note").stringValue()))
                        .as("узел %s — объект с %s", path, NODE_MARKER).isTrue();
                yield NODE_MARKER;
            }
            default -> throw new IllegalArgumentException("неподдерживаемый вид узла для проверки утечки: " + kind);
        };
    }

    private static JsonNode nodeAt(JsonNode data, String path) {
        if ("cancel_comment".equals(path) || "policy_usk_number".equals(path)) {
            return data.path(path);
        }
        Matcher matcher = participantMatcher(path);
        JsonNode node = data.path("participants").path(Integer.parseInt(matcher.group(1)));
        for (String segment : matcher.group(2).split("\\.")) {
            node = node.path(segment);
        }
        return node;
    }

    private static Matcher participantMatcher(String path) {
        Matcher matcher = PARTICIPANT_FIELD.matcher(path);
        if (!matcher.matches() || !OPTIONAL_PARTICIPANT_FIELDS.contains(matcher.group(2))) {
            throw new IllegalArgumentException("неизвестное необязательное поле задачи: " + path);
        }
        return matcher;
    }

    private static void applyPath(ObjectNode participant, String relativePath, String kind) {
        String[] segments = relativePath.split("\\.");
        ObjectNode container = participant;
        for (int i = 0; i < segments.length - 1; i++) {
            container = (ObjectNode) container.get(segments[i]);
        }
        applyValue(container, segments[segments.length - 1], kind);
    }

    private static void applyValue(ObjectNode container, String name, String kind) {
        switch (kind) {
            case ABSENT_KEY -> container.remove(name);
            case EXPLICIT_NULL -> container.putNull(name);
            case NUMBER -> container.put(name, NUMBER_VALUE);
            case BOOLEAN -> container.put(name, true);
            case ARRAY -> container.putArray(name).add(NODE_MARKER);
            case OBJECT -> container.putObject(name).put("note", NODE_MARKER);
            case EMPTY -> container.put(name, "");
            case BLANK -> container.put(name, "   ");
            default -> throw new IllegalArgumentException("неизвестный вид значения: " + kind);
        }
    }
}
