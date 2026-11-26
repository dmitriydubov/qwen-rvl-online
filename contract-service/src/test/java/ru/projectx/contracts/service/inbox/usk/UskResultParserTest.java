package ru.projectx.contracts.service.inbox.usk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.projectx.contracts.exception.ContractViolationException;
import ru.projectx.contracts.message.usk.IssuedDocument;
import ru.projectx.contracts.message.usk.UskResult;
import ru.projectx.contracts.service.inbox.parse.FieldPresence;
import ru.projectx.contracts.service.inbox.parse.MessageBodies;
import ru.projectx.contracts.service.usk.UskResultLayout;
import ru.projectx.contracts.support.LmPolicyResultBodies;
import ru.projectx.contracts.support.UskResultBodies;
import tools.jackson.databind.json.JsonMapper;

class UskResultParserTest {

    private static final MessageBodies BODIES = new MessageBodies(JsonMapper.builder().build());
    private static final UskResultLayout LM_POLICY = new UskResultLayout("ISSUED", "policy_usk_id",
            "policy_usk_number", FieldPresence.OPTIONAL, FieldPresence.OPTIONAL);
    private static final UskResultLayout KM_CONTRACT = new UskResultLayout("REGISTERED", "contract_usk_id",
            "contract_usk_number", FieldPresence.REQUIRED, FieldPresence.REQUIRED);

    @Nested
    class LmPolicyIssued {

        private final UskResultParser parser = new UskResultParser(BODIES, LM_POLICY);

        @Test
        void issuedResult_isParsedWithUskIdentifiersPolicyAndDocuments() {
            UskResult result = parser.parse(LmPolicyResultBodies.issued().toJson());

            assertThat(result).isEqualTo(new UskResult.Issued(UUID.fromString(LmPolicyResultBodies.POLICY_ID),
                    UUID.fromString(LmPolicyResultBodies.POLICY_USK_ID), "LM-000012204",
                    UUID.fromString(LmPolicyResultBodies.POLICY_EXTERNAL_ID), "0555123456",
                    List.of(new IssuedDocument("POLICY", LmPolicyResultBodies.FILE_URL, 96256L))));
        }

        @Test
        void failedResult_isParsedWithCodeAndMessageAsReceived() {
            UskResult result = parser.parse(LmPolicyResultBodies.failed().toJson());

            assertThat(result).isEqualTo(new UskResult.Failed(UUID.fromString(LmPolicyResultBodies.POLICY_ID),
                    "INSURED_NOT_COVERED", "Стенд: застрахованный не входит в корпоративный договор"));
        }

        @Test
        void absentOrEmptyDocuments_giveNoDocuments() {
            assertThat(parser.parse(LmPolicyResultBodies.issued().withoutDataField("documents").toJson()))
                    .extracting(result -> ((UskResult.Issued) result).documents()).isEqualTo(List.of());
            assertThat(parser.parse(LmPolicyResultBodies.issued().withEmptyDocuments().toJson()))
                    .extracting(result -> ((UskResult.Issued) result).documents()).isEqualTo(List.of());
        }

        @Test
        void uskNumberAndFileSize_mayBeAbsentOrNull() {
            UskResult.Issued absent = (UskResult.Issued) parser.parse(LmPolicyResultBodies.issued()
                    .withoutDataField("policy_usk_number").withoutDocumentField(0, "file_size").toJson());
            UskResult.Issued explicitNull = (UskResult.Issued) parser.parse(LmPolicyResultBodies.issued()
                    .withDataJson("policy_usk_number", "null").toJson());

            assertThat(absent.uskNumber()).isNull();
            assertThat(absent.documents()).containsExactly(new IssuedDocument("POLICY", LmPolicyResultBodies.FILE_URL,
                    null));
            assertThat(explicitNull.uskNumber()).isNull();
        }

        @ParameterizedTest
        @ValueSource(strings = {"987654", "true", "[\"Тестов-Узел\"]", "{\"note\":\"Тестов-Узел\"}"})
        void nonStringUskNumberWhenIssued_isViolationNamingTheField(String jsonValue) {
            assertThatThrownBy(() -> parser.parse(LmPolicyResultBodies.issued()
                    .withDataJson("policy_usk_number", jsonValue).toJson()))
                    .isInstanceOf(ContractViolationException.class)
                    .hasMessage("недопустимое значение поля data.policy_usk_number")
                    .hasNoCause();
        }

        @ParameterizedTest
        @ValueSource(strings = {"987654", "true", "[\"Тестов-Узел\"]", "{\"note\":\"Тестов-Узел\"}"})
        void nonStringUskNumberWhenFailed_isIgnored(String jsonValue) {
            UskResult result = parser.parse(LmPolicyResultBodies.failed()
                    .withDataJson("policy_usk_number", jsonValue).toJson());

            assertThat(result).isEqualTo(new UskResult.Failed(UUID.fromString(LmPolicyResultBodies.POLICY_ID),
                    "INSURED_NOT_COVERED", "Стенд: застрахованный не входит в корпоративный договор"));
        }

        @Test
        void missingPolicyWhenIssued_isViolationNamingTheField() {
            assertThatThrownBy(() -> parser.parse(LmPolicyResultBodies.issued().withoutDataField("policy").toJson()))
                    .isInstanceOf(ContractViolationException.class)
                    .hasMessage("нет обязательного поля data.policy");
        }

        @Test
        void missingUskIdWhenIssued_isViolationNamingTheField() {
            assertThatThrownBy(() -> parser.parse(LmPolicyResultBodies.issued().withoutDataField("policy_usk_id")
                    .toJson()))
                    .isInstanceOf(ContractViolationException.class)
                    .hasMessage("нет обязательного поля data.policy_usk_id");
        }

        @Test
        void missingErrorCodeWhenFailed_isViolationNamingTheField() {
            assertThatThrownBy(() -> parser.parse(LmPolicyResultBodies.failed().withoutDataField("error_code").toJson()))
                    .isInstanceOf(ContractViolationException.class)
                    .hasMessage("нет обязательного поля data.error_code");
        }

        @ParameterizedTest
        @ValueSource(strings = {"id", "status"})
        void missingRequiredFieldOfIssuedResult_isViolationNamingTheField(String field) {
            assertThatThrownBy(() -> parser.parse(LmPolicyResultBodies.issued().withoutDataField(field).toJson()))
                    .isInstanceOf(ContractViolationException.class)
                    .hasMessage("нет обязательного поля data." + field);
        }

        @ParameterizedTest
        @ValueSource(strings = {"external_id", "number"})
        void missingRequiredPolicyField_isViolationNamingTheField(String field) {
            assertThatThrownBy(() -> parser.parse(LmPolicyResultBodies.issued().withoutPolicyField(field).toJson()))
                    .isInstanceOf(ContractViolationException.class)
                    .hasMessage("нет обязательного поля data.policy." + field);
        }

        @ParameterizedTest
        @ValueSource(strings = {"id", "error_message"})
        void missingRequiredFieldOfFailedResult_isViolationNamingTheField(String field) {
            assertThatThrownBy(() -> parser.parse(LmPolicyResultBodies.failed().withoutDataField(field).toJson()))
                    .isInstanceOf(ContractViolationException.class)
                    .hasMessage("нет обязательного поля data." + field);
        }

        @ParameterizedTest
        @ValueSource(strings = {"doc_type", "file_url"})
        void documentWithoutRequiredField_isViolationNamingTheField(String field) {
            assertThatThrownBy(() -> parser.parse(LmPolicyResultBodies.issued().withoutDocumentField(0, field)
                    .toJson()))
                    .isInstanceOf(ContractViolationException.class)
                    .hasMessage("нет обязательного поля data.documents[0]." + field);
        }

        @Test
        void unknownStatus_isViolationNamingTheField() {
            assertThatThrownBy(() -> parser.parse(LmPolicyResultBodies.issued().withDataField("status", "FAILED")
                    .toJson()))
                    .isInstanceOf(ContractViolationException.class)
                    .hasMessage("недопустимое значение поля data.status");
        }

        @Test
        void unknownOrNonUskDocumentType_isViolationNamingTheDocument() {
            assertThatThrownBy(() -> parser.parse(LmPolicyResultBodies.issued()
                    .withDocumentField(0, "doc_type", "RECEIPT").toJson()))
                    .isInstanceOf(ContractViolationException.class)
                    .hasMessage("недопустимое значение поля data.documents[0].doc_type");
            assertThatThrownBy(() -> parser.parse(LmPolicyResultBodies.issued()
                    .withDocumentField(0, "doc_type", "MEDICAL_CERTIFICATE").toJson()))
                    .isInstanceOf(ContractViolationException.class)
                    .hasMessage("недопустимое значение поля data.documents[0].doc_type");
        }

        @Test
        void documentWithoutUrlOrWithUnreadableSize_isViolationNamingTheField() {
            assertThatThrownBy(() -> parser.parse(LmPolicyResultBodies.issued()
                    .withDocumentField(0, "file_url", null).toJson()))
                    .isInstanceOf(ContractViolationException.class)
                    .hasMessage("нет обязательного поля data.documents[0].file_url");
            assertThatThrownBy(() -> parser.parse(LmPolicyResultBodies.issued()
                    .withDocumentField(0, "file_size", "много").toJson()))
                    .isInstanceOf(ContractViolationException.class)
                    .hasMessage("недопустимое значение поля data.documents[0].file_size");
        }

        @Test
        void documentsThatAreNotAnArray_isViolationNamingTheField() {
            assertThatThrownBy(() -> parser.parse(LmPolicyResultBodies.issued().withDataField("documents", "нет")
                    .toJson()))
                    .isInstanceOf(ContractViolationException.class)
                    .hasMessage("недопустимое значение поля data.documents");
        }
    }

    @Nested
    class KmContractRegistered {

        private final UskResultParser parser = new UskResultParser(BODIES, KM_CONTRACT);

        @Test
        void registeredResult_isParsedWithContractIdentifiersPolicyAndDocumentsInOrder() {
            UskResult result = parser.parse(UskResultBodies.registered().toJson());

            assertThat(result).isEqualTo(new UskResult.Issued(UUID.fromString(UskResultBodies.CONTRACT_ID),
                    UUID.fromString(UskResultBodies.CONTRACT_USK_ID), "DKM-000004417",
                    UUID.fromString(UskResultBodies.POLICY_EXTERNAL_ID), "0987654321",
                    List.of(new IssuedDocument("CONTRACT", UskResultBodies.CONTRACT_URL, 205312L),
                            new IssuedDocument("POLICY", UskResultBodies.POLICY_URL, 118784L))));
        }

        @Test
        void rejectedResult_isParsedWithCodeAndMessageAsReceived() {
            assertThat(parser.parse(UskResultBodies.rejected().toJson())).isEqualTo(new UskResult.Failed(
                    UUID.fromString(UskResultBodies.CONTRACT_ID), "CONTRACT_REJECTED",
                    "Стенд: регистрация договора невозможна"));
        }

        @Test
        void emptyDocuments_areAllowedButAbsentDocuments_areAViolation() {
            assertThat(parser.parse(UskResultBodies.registered().withEmptyDocuments().toJson()))
                    .extracting(result -> ((UskResult.Issued) result).documents()).isEqualTo(List.of());
            assertThatThrownBy(() -> parser.parse(UskResultBodies.registered().withoutDataField("documents").toJson()))
                    .isInstanceOf(ContractViolationException.class)
                    .hasMessage("нет обязательного поля data.documents");
        }

        @Test
        void missingContractIdentifiersOrPolicy_areViolationsNamingTheField() {
            assertThatThrownBy(() -> parser.parse(UskResultBodies.registered().withoutDataField("contract_usk_id")
                    .toJson())).isInstanceOf(ContractViolationException.class)
                    .hasMessage("нет обязательного поля data.contract_usk_id");
            assertThatThrownBy(() -> parser.parse(UskResultBodies.registered().withoutDataField("contract_usk_number")
                    .toJson())).isInstanceOf(ContractViolationException.class)
                    .hasMessage("нет обязательного поля data.contract_usk_number");
            assertThatThrownBy(() -> parser.parse(UskResultBodies.registered().withoutDataField("policy").toJson()))
                    .isInstanceOf(ContractViolationException.class)
                    .hasMessage("нет обязательного поля data.policy");
        }

        @Test
        void missingErrorCode_unknownStatus_andForeignDocumentTypes_areViolations() {
            assertThatThrownBy(() -> parser.parse(UskResultBodies.rejected().withoutDataField("error_code").toJson()))
                    .isInstanceOf(ContractViolationException.class)
                    .hasMessage("нет обязательного поля data.error_code");
            assertThatThrownBy(() -> parser.parse(UskResultBodies.registered().withDataField("status", "FAILED")
                    .toJson())).isInstanceOf(ContractViolationException.class)
                    .hasMessage("недопустимое значение поля data.status");
            assertThatThrownBy(() -> parser.parse(UskResultBodies.registered()
                    .withDocumentField(0, "doc_type", "MEDICAL_CERTIFICATE").toJson()))
                    .isInstanceOf(ContractViolationException.class)
                    .hasMessage("недопустимое значение поля data.documents[0].doc_type");
            assertThatThrownBy(() -> parser.parse(UskResultBodies.registered()
                    .withDocumentField(0, "doc_type", "INVOICE").toJson()))
                    .isInstanceOf(ContractViolationException.class)
                    .hasMessage("недопустимое значение поля data.documents[0].doc_type");
        }
    }
}
