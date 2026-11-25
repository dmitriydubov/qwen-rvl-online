package ru.projectx.contracts.service.inbox.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import ru.projectx.contracts.exception.ContractViolationException;
import ru.projectx.contracts.message.task.ContractTask;
import ru.projectx.contracts.message.ParticipantDocument;
import ru.projectx.contracts.message.task.ParticipantPerson;
import ru.projectx.contracts.message.task.TaskParticipant;
import ru.projectx.contracts.service.inbox.parse.MessageBodies;
import ru.projectx.contracts.support.TaskBodies;
import tools.jackson.databind.json.JsonMapper;

class ContractTaskParserTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<String> OPTIONAL_FIELDS = List.of("program_id", "personal_data.middle_name",
            "personal_data.relation_type", "personal_data.address", "personal_data.document.series",
            "personal_data.document.issued_by", "personal_data.document.issued_at", "personal_data.document.division_code");

    private final ContractTaskParser parser =
            new ContractTaskParser(new MessageBodies(JsonMapper.builder().build()));

    static Stream<Arguments> nonStringOptionalFields() {
        return OPTIONAL_FIELDS.stream()
                .flatMap(path -> Stream.of("5", "true", "[]", "{}").map(node -> Arguments.of(path, node)));
    }

    @ParameterizedTest
    @MethodSource("nonStringOptionalFields")
    void nonStringOptionalField_isViolationNamingTheFullPath(String path, String node) {
        TaskBodies body = TaskBodies.upserted().withParticipantNode(1, path, JSON.readTree(node));

        assertThatThrownBy(() -> parser.parse(body.toJson()))
                .isInstanceOf(ContractViolationException.class)
                .hasMessage("недопустимое значение поля data.participants[1]." + path);
    }

    @Test
    void nullOptionalFields_areParsedAsEmpty() {
        TaskBodies body = TaskBodies.upserted();
        for (String path : OPTIONAL_FIELDS) {
            body.withParticipantNode(1, path, JSON.readTree("null"));
        }

        TaskParticipant participant = parser.parse(body.toJson()).participants().get(1);

        assertThat(participant.programId()).isNull();
        assertThat(participant.person().middleName()).isNull();
        assertThat(participant.person().relationType()).isNull();
        assertThat(participant.person().address()).isNull();
        assertThat(participant.person().document().series()).isNull();
        assertThat(participant.person().document().issuedBy()).isNull();
        assertThat(participant.person().document().issuedAt()).isNull();
        assertThat(participant.person().document().divisionCode()).isNull();
    }

    @Test
    void completeTask_isParsedWithEveryParticipantInOrder() {
        ContractTask task = parser.parse(TaskBodies.upserted().toJson());

        assertThat(task.entityVersion()).isEqualTo(1);
        assertThat(task.individualRequestId()).isEqualTo(UUID.fromString("61a7982c-a0b4-4bbf-9527-60b40b752803"));
        assertThat(task.insuranceContractId()).isEqualTo(UUID.fromString("e12aab1a-1582-4b3a-af36-d30e637953f1"));
        assertThat(task.insurerPolicyId()).isEqualTo(UUID.fromString("a23ba061-f742-4b20-8b95-36a0e525c087"));
        assertThat(task.dateFrom()).isEqualTo(LocalDate.parse("2026-04-10"));
        assertThat(task.dateTo()).isEqualTo(LocalDate.parse("2026-04-24"));
        assertThat(task.participants()).hasSize(2);
        TaskParticipant spouse = task.participants().get(1);
        assertThat(spouse).isEqualTo(new TaskParticipant(1, UUID.fromString("9275471c-d2fe-484f-97a6-d6d7b1267b5e"),
                true, new BigDecimal("34000.00"), null, new ParticipantPerson("Тестова", "Теста", "Тестовна",
                        LocalDate.parse("1988-07-02"), "FEMALE", "SPOUSE",
                        new ParticipantDocument("RF_PASSPORT", "4512", "654321", "Тестовый орган",
                                LocalDate.parse("2012-08-15"), "770-001"), "Тестовый адрес")));
        assertThat(task.insured().id()).isEqualTo(UUID.fromString("ae8312c3-350b-4d0b-806c-5e2460e74dbc"));
    }

    @Test
    void optionalPersonalFields_mayBeAbsent() {
        TaskBodies body = TaskBodies.upserted()
                .withoutParticipantField(1, "personal_data.middle_name")
                .withoutParticipantField(1, "personal_data.relation_type")
                .withoutParticipantField(1, "personal_data.address")
                .withoutParticipantField(1, "personal_data.document.series")
                .withoutParticipantField(1, "personal_data.document.issued_by")
                .withoutParticipantField(1, "personal_data.document.issued_at")
                .withoutParticipantField(1, "personal_data.document.division_code");

        ParticipantPerson person = parser.parse(body.toJson()).participants().get(1).person();

        assertThat(person.middleName()).isNull();
        assertThat(person.relationType()).isNull();
        assertThat(person.address()).isNull();
        assertThat(person.document().series()).isNull();
        assertThat(person.document().issuedBy()).isNull();
        assertThat(person.document().issuedAt()).isNull();
        assertThat(person.document().divisionCode()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"individual_request_id", "insurance_contract_id", "insurer_policy_id", "date_to",
            "participants"})
    void missingRequiredTaskField_isViolationNamingTheField(String field) {
        assertThatThrownBy(() -> parser.parse(TaskBodies.upserted().withoutDataField(field).toJson()))
                .isInstanceOf(ContractViolationException.class)
                .hasMessage("нет обязательного поля data." + field);
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "is_relative", "amount_due", "personal_data.first_name", "personal_data.birth_date",
            "personal_data.gender", "personal_data.document.type", "personal_data.document.number"})
    void missingRequiredParticipantField_isViolationNamingTheField(String field) {
        assertThatThrownBy(() -> parser.parse(TaskBodies.upserted().withoutParticipantField(1, field).toJson()))
                .isInstanceOf(ContractViolationException.class)
                .hasMessage("нет обязательного поля data.participants[1]." + field);
    }

    @Test
    void programOfParticipant_isParsedWhenPresent() {
        TaskBodies body = TaskBodies.upserted().withParticipantField(1, "program_id", "2f6a0c1e-3d5b-4a7c-9e8f-0b1c2d3e4f5a");

        assertThat(parser.parse(body.toJson()).participants().get(1).programId())
                .isEqualTo(UUID.fromString("2f6a0c1e-3d5b-4a7c-9e8f-0b1c2d3e4f5a"));
    }

    @Test
    void unreadableProgramOfParticipant_isViolationNamingTheField() {
        TaskBodies body = TaskBodies.upserted().withParticipantField(1, "program_id", "1-2-3-4-5");

        assertThatThrownBy(() -> parser.parse(body.toJson()))
                .isInstanceOf(ContractViolationException.class)
                .hasMessage("недопустимое значение поля data.participants[1].program_id");
    }

    @Test
    void missingEntityVersion_isViolationNamingTheField() {
        assertThatThrownBy(() -> parser.parse(TaskBodies.upserted().withoutMetaField("entity_version").toJson()))
                .isInstanceOf(ContractViolationException.class)
                .hasMessage("нет обязательного поля meta.entity_version");
    }

    @Test
    void missingDateFrom_isViolationNamingTheField() {
        assertThatThrownBy(() -> parser.parse(TaskBodies.upserted().withoutDataField("date_from").toJson()))
                .isInstanceOf(ContractViolationException.class)
                .hasMessage("нет обязательного поля data.date_from");
    }

    @Test
    void emptyParticipants_isViolation() {
        assertThatThrownBy(() -> parser.parse(TaskBodies.upserted().withoutParticipants().toJson()))
                .isInstanceOf(ContractViolationException.class)
                .hasMessage("в data.participants нет ни одного участника");
    }

    @Test
    void negativeAmountDue_isViolationNamingTheParticipant() {
        assertThatThrownBy(() -> parser.parse(TaskBodies.upserted().withAmountDue(0, "-1.00").toJson()))
                .isInstanceOf(ContractViolationException.class)
                .hasMessage("поле data.participants[0].amount_due отрицательное");
    }

    @Test
    void zeroAmountDue_isAccepted() {
        assertThat(parser.parse(TaskBodies.upserted().withAmountDue(1, "0.00").toJson()).participants().get(1).pays())
                .isFalse();
    }

    @Test
    void twoInsuredPersons_isViolationCountingThem() {
        assertThatThrownBy(() -> parser.parse(TaskBodies.upserted().withRelative(1, false).toJson()))
                .isInstanceOf(ContractViolationException.class)
                .hasMessage("в составе поездки должно быть ровно одно застрахованное лицо, а не 2");
    }

    @Test
    void noInsuredPerson_isViolationCountingThem() {
        assertThatThrownBy(() -> parser.parse(TaskBodies.upserted().withRelative(0, true).toJson()))
                .isInstanceOf(ContractViolationException.class)
                .hasMessage("в составе поездки должно быть ровно одно застрахованное лицо, а не 0");
    }

    @Test
    void missingPersonalData_isViolationNamingTheParticipant() {
        assertThatThrownBy(() -> parser.parse(TaskBodies.upserted().withoutParticipantField(1, "personal_data").toJson()))
                .hasMessage("нет обязательного поля data.participants[1].personal_data");
        assertThatThrownBy(() -> parser.parse(
                TaskBodies.upserted().withoutParticipantField(1, "personal_data.document").toJson()))
                .hasMessage("нет обязательного поля data.participants[1].personal_data.document");
        assertThatThrownBy(() -> parser.parse(
                TaskBodies.upserted().withoutParticipantField(1, "personal_data.last_name").toJson()))
                .hasMessage("нет обязательного поля data.participants[1].personal_data.last_name");
    }

    @Test
    void bodyWithoutData_isViolation() {
        assertThatThrownBy(() -> parser.parse("{\"meta\":{}}"))
                .isInstanceOf(ContractViolationException.class)
                .hasMessage("нет обязательного поля data");
    }

    @Test
    void unreadableBody_isViolationWithoutItsContent() {
        assertThatThrownBy(() -> parser.parse("{Тестов"))
                .isInstanceOf(ContractViolationException.class)
                .hasMessage("тело не читается как JSON");
    }
}
