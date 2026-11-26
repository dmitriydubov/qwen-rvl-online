package ru.projectx.contracts.service.inbox.parse;

import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import ru.projectx.contracts.exception.ContractViolationException;
import tools.jackson.databind.JsonNode;

public record OptionalField(JsonField field) {

    public @Nullable String text() {
        return field.isAbsent() ? null : field.text();
    }

    public @Nullable UUID uuid() {
        return field.isAbsent() ? null : field.uuid();
    }

    public @Nullable LocalDate date() {
        return field.isAbsent() ? null : field.date();
    }

    public @Nullable Long longInteger() {
        if (field.isAbsent()) {
            return null;
        }
        if (notLong()) {
            throw ContractViolationException.invalidValue(field.path());
        }
        return field.value().longValue();
    }

    private boolean notLong() {
        JsonNode value = field.value();
        return !value.isIntegralNumber() || !value.canConvertToLong();
    }
}
