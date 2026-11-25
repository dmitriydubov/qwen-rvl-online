package ru.projectx.contracts.acceptance;

import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

final class FieldNodes {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PERSONAL_DATA = "Секретов Секрет Секретович";
    private static final Map<String, String> KINDS = Map.of(
            "null", "null",
            "число", "5",
            "boolean", "true",
            "пустой массив", "[]",
            "пустой объект", "{}",
            "объект с ПДн", "{\"fio\": \"" + PERSONAL_DATA + "\"}",
            "массив со строкой", "[\"" + PERSONAL_DATA + "\"]");

    private FieldNodes() {
    }

    static void put(ObjectNode root, String path, String kind, String string) {
        String[] names = path.split("\\.");
        ObjectNode container = root;
        for (int i = 0; i < names.length - 1; i++) {
            container = (ObjectNode) container.get(names[i]);
        }
        String name = names[names.length - 1];
        if ("нет".equals(kind)) {
            container.remove(name);
            return;
        }
        container.set(name, node(kind, string));
    }

    private static JsonNode node(String kind, String string) {
        if ("строка".equals(kind)) {
            return JSON.getNodeFactory().stringNode(string);
        }
        String json = KINDS.get(kind);
        if (json == null) {
            throw new IllegalArgumentException("неизвестный вид узла: " + kind);
        }
        return JSON.readTree(json);
    }
}
