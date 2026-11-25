package ru.projectx.contracts.service.inbox.parse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.projectx.contracts.exception.ContractViolationException;
import tools.jackson.databind.json.JsonMapper;

class OptionalFieldTest {

    private static final String BODY = """
            {"data": {"id": "61a7982c-a0b4-4bbf-9527-60b40b752803", "date": "2026-04-10", "amount": 34000.00,
                      "name": "Тестов", "number": 5, "nothing": null,
                      "beyondInt": 99999999999, "beyondLong": 99999999999999999999,
                      "empty": "", "spaces": "   "}}
            """;

    private static final String NON_STRING_BODY = """
            {"data": {"zero": 0, "number": 5, "fraction": 12.34, "yes": true, "no": false,
                      "emptyArray": [], "personalArray": ["ПДн"], "emptyObject": {}, "personalObject": {"fio": "ПДн"},
                      "nothing": null}}
            """;

    private final JsonFields data = fields(BODY);
    private final JsonFields nonString = fields(NON_STRING_BODY);

    private static JsonFields fields(String body) {
        return new JsonFields(JsonMapper.builder().build().readTree(body), "").requiredObject("data");
    }

    @Test
    void presentTextValues_areReadFromTheirFields() {
        assertThat(data.field("name").optional().text()).isEqualTo("Тестов");
        assertThat(data.field("id").optional().uuid()).isEqualTo(UUID.fromString("61a7982c-a0b4-4bbf-9527-60b40b752803"));
        assertThat(data.field("date").optional().date()).isEqualTo(LocalDate.parse("2026-04-10"));
    }

    @Test
    void absentAndNullValues_areNullForTextGuidAndDate() {
        assertThat(data.field("missing").optional().text()).isNull();
        assertThat(data.field("nothing").optional().text()).isNull();
        assertThat(data.field("missing").optional().uuid()).isNull();
        assertThat(data.field("nothing").optional().uuid()).isNull();
        assertThat(data.field("missing").optional().date()).isNull();
        assertThat(data.field("nothing").optional().date()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"zero", "number", "fraction", "yes", "no", "emptyArray", "personalArray", "emptyObject",
            "personalObject"})
    void nonStringValues_areViolationsWithoutTheValueForTextGuidAndDate(String field) {
        assertThatThrownBy(() -> nonString.field(field).optional().text())
                .isInstanceOf(ContractViolationException.class)
                .hasMessage("недопустимое значение поля data." + field).hasNoCause();
        assertThatThrownBy(() -> nonString.field(field).optional().uuid())
                .isInstanceOf(ContractViolationException.class)
                .hasMessage("недопустимое значение поля data." + field).hasNoCause();
        assertThatThrownBy(() -> nonString.field(field).optional().date())
                .isInstanceOf(ContractViolationException.class)
                .hasMessage("недопустимое значение поля data." + field).hasNoCause();
    }

    @Test
    void absentNullAndNonStringValues_followTheSameRuleWhenReadWithBigDecimalFloats() {
        JsonFields read = new MessageBodies(JsonMapper.builder().build()).data(NON_STRING_BODY);
        assertThat(read.field("missing").optional().text()).isNull();
        assertThat(read.field("nothing").optional().uuid()).isNull();
        assertThat(read.field("nothing").optional().date()).isNull();
        assertThatThrownBy(() -> read.field("fraction").optional().text())
                .isInstanceOf(ContractViolationException.class)
                .hasMessage("недопустимое значение поля data.fraction").hasNoCause();
        assertThatThrownBy(() -> read.field("fraction").optional().uuid())
                .isInstanceOf(ContractViolationException.class)
                .hasMessage("недопустимое значение поля data.fraction").hasNoCause();
        assertThatThrownBy(() -> read.field("fraction").optional().date())
                .isInstanceOf(ContractViolationException.class)
                .hasMessage("недопустимое значение поля data.fraction").hasNoCause();
    }

    @Test
    void emptyAndBlankStrings_areTextButNotGuidOrDate() {
        assertThat(data.field("empty").optional().text()).isEqualTo("");
        assertThat(data.field("spaces").optional().text()).isEqualTo("   ");
        assertThatThrownBy(() -> data.field("empty").optional().uuid())
                .isInstanceOf(ContractViolationException.class)
                .hasMessage("недопустимое значение поля data.empty");
        assertThatThrownBy(() -> data.field("spaces").optional().date())
                .isInstanceOf(ContractViolationException.class)
                .hasMessage("недопустимое значение поля data.spaces");
    }

    @Test
    void malformedTextValues_areViolationsWithoutTheValue() {
        assertThatThrownBy(() -> data.field("name").optional().uuid()).isInstanceOf(ContractViolationException.class)
                .hasMessage("недопустимое значение поля data.name");
        assertThatThrownBy(() -> data.field("name").optional().date())
                .hasMessage("недопустимое значение поля data.name").hasCauseInstanceOf(DateTimeParseException.class);
    }

    @Test
    void optionalLongField_isNullWhenAbsentOrNullAndOtherwiseAWholeNumberThatFitsLong() {
        assertThat(data.field("missing").optional().longInteger()).isNull();
        assertThat(data.field("nothing").optional().longInteger()).isNull();
        assertThat(data.field("number").optional().longInteger()).isEqualTo(5L);
        assertThat(data.field("beyondInt").optional().longInteger()).isEqualTo(99999999999L);
        assertThatThrownBy(() -> data.field("amount").optional().longInteger())
                .isInstanceOf(ContractViolationException.class).hasMessage("недопустимое значение поля data.amount");
        assertThatThrownBy(() -> data.field("beyondLong").optional().longInteger())
                .hasMessage("недопустимое значение поля data.beyondLong");
        assertThatThrownBy(() -> data.field("name").optional().longInteger())
                .hasMessage("недопустимое значение поля data.name");
    }
}