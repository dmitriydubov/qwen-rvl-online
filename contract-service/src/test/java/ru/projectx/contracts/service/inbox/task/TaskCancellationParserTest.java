package ru.projectx.contracts.service.inbox.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.projectx.contracts.exception.ContractViolationException;
import ru.projectx.contracts.message.task.ContractTaskCancellation;
import ru.projectx.contracts.service.inbox.parse.MessageBodies;
import ru.projectx.contracts.support.TaskBodies;
import tools.jackson.databind.json.JsonMapper;

class TaskCancellationParserTest {

    private final TaskCancellationParser parser =
            new TaskCancellationParser(new MessageBodies(JsonMapper.builder().build()));

    @Test
    void cancellation_isParsedWithMomentReasonAndComment() {
        ContractTaskCancellation cancellation = parser.parse(TaskBodies.cancelled("INSURED_REFUSED").toJson());

        assertThat(cancellation).isEqualTo(new ContractTaskCancellation(
                UUID.fromString("61a7982c-a0b4-4bbf-9527-60b40b752803"), Instant.parse("2026-02-12T11:30:00Z"),
                "INSURED_REFUSED", "Стенд: комментарий"));
    }

    @Test
    void commentIsOptional() {
        assertThat(parser.parse(TaskBodies.cancelled("EXPIRED").withoutDataField("cancel_comment").toJson())
                .cancelComment()).isNull();
        assertThat(parser.parse(TaskBodies.cancelled("EXPIRED").withDataJson("cancel_comment", "null").toJson())
                .cancelComment()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"987654", "true", "[\"Тестов-Узел\"]", "{\"note\":\"Тестов-Узел\"}"})
    void nonStringComment_isViolationNamingTheField(String jsonValue) {
        assertThatThrownBy(() -> parser.parse(
                TaskBodies.cancelled("EXPIRED").withDataJson("cancel_comment", jsonValue).toJson()))
                .isInstanceOf(ContractViolationException.class)
                .hasMessage("недопустимое значение поля data.cancel_comment")
                .hasNoCause();
    }

    @Test
    void missingReason_isViolationNamingTheField() {
        assertThatThrownBy(() -> parser.parse(TaskBodies.cancelled("EXPIRED").withoutDataField("cancel_reason").toJson()))
                .isInstanceOf(ContractViolationException.class)
                .hasMessage("нет обязательного поля data.cancel_reason");
    }

    @ParameterizedTest
    @ValueSource(strings = {"individual_request_id", "cancelled_at"})
    void missingRequiredField_isViolationNamingTheField(String field) {
        assertThatThrownBy(() -> parser.parse(TaskBodies.cancelled("EXPIRED").withoutDataField(field).toJson()))
                .isInstanceOf(ContractViolationException.class)
                .hasMessage("нет обязательного поля data." + field);
    }

    @Test
    void malformedMoment_isViolationWithoutTheValue() {
        assertThatThrownBy(() -> parser.parse(TaskBodies.cancelled("EXPIRED").withDataField("cancelled_at", "вчера").toJson()))
                .isInstanceOf(ContractViolationException.class)
                .hasMessage("недопустимое значение поля data.cancelled_at");
    }
}
