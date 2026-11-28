package ru.projectx.contracts.service.km.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.net.InetAddress;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import ru.projectx.contracts.entity.contract.AcceptanceChannel;
import ru.projectx.contracts.entity.contract.Contract;
import ru.projectx.contracts.entity.contract.ContractAcceptance;
import ru.projectx.contracts.entity.party.ContractPerson;
import ru.projectx.contracts.entity.contract.OfferAcceptance;
import ru.projectx.contracts.entity.document.DocumentAttachments;
import ru.projectx.contracts.entity.party.PersonOwner;
import ru.projectx.contracts.entity.party.PersonRole;
import ru.projectx.contracts.entity.document.PrintedForm;
import ru.projectx.contracts.entity.document.PrintedFormType;
import ru.projectx.contracts.entity.document.StoredFile;
import ru.projectx.contracts.repository.issuance.ContractAcceptanceRepository;
import ru.projectx.contracts.repository.issuance.ContractPersonRepository;
import ru.projectx.contracts.repository.issuance.PrintedFormRepository;
import ru.projectx.contracts.service.issuance.PersonSnapshots;
import ru.projectx.contracts.service.reference.ExternalIdentifiers;
import ru.projectx.contracts.support.Issuances;
import ru.projectx.contracts.support.Tasks;

class KmContractSubmissionFactsTest {

    private static final Instant NOW = Instant.parse("2026-02-10T07:32:00Z");
    private static final Instant ACCEPTED_AT = Instant.parse("2026-02-10T07:04:58Z");
    private static final UUID CONTRACT_ID = UUID.fromString("0f4b8e2a-6c13-4d7e-b9a1-2e5c7d3f8a64");
    private static final UUID FIRST_DOCUMENT = UUID.fromString("3c2f8e10-5a7b-4d21-9f6e-1b0a7c4d8e21");
    private static final UUID SECOND_DOCUMENT = UUID.fromString("1a4c7e2f-9b30-4d85-a6f1-c2e8b05d7f39");
    private static final ExternalIdentifiers IDENTIFIERS = new ExternalIdentifiers(
            UUID.fromString("6b02f1cc-9d47-4e35-8a20-7f1c3e5b09a2"), UUID.fromString("8c41e2ab-55d0-4a17-9b63-2f0e7c1d4a88"),
            UUID.fromString("1d9a6e77-4c02-4b88-a15e-30f7b2c9de41"));
    private static final StoredFile JPEG = new StoredFile("certificates/0b6c/3c2f.jpg", "1".repeat(64), 76L, "image/jpeg");
    private static final StoredFile PDF = new StoredFile("certificates/0b6c/1a4c.pdf", "2".repeat(64), 20000L,
            "application/pdf");

    private final ContractPersonRepository persons = mock();
    private final ContractAcceptanceRepository acceptances = mock();
    private final PrintedFormRepository forms = mock();
    private final KmContractSubmissionFacts facts = new KmContractSubmissionFacts(persons, acceptances,
            new InsuredCertificates(forms));
    private final Contract contract = Issuances.contract(CONTRACT_ID.toString(), Tasks.SPOUSE, "DKM-2026-000001");

    @BeforeEach
    void contractHasBothPartiesAnAcceptanceAndTwoCertificatesOfTheInsured() {
        given(persons.findByContractIdOrderByRole(CONTRACT_ID)).willReturn(List.of(
                person(PersonRole.POLICYHOLDER, PersonSnapshots.of(Tasks.insured("0.00"))),
                person(PersonRole.INSURED, PersonSnapshots.of(Tasks.spouse("34000.00")))));
        given(acceptances.findByContractId(CONTRACT_ID)).willReturn(Optional.of(ContractAcceptance.record(CONTRACT_ID,
                new OfferAcceptance(1, UUID.fromString("b0a1c7e4-52d9-4f31-8c06-7e3a9b1d2f45"), ACCEPTED_AT,
                        AcceptanceChannel.WEB, InetAddress.getLoopbackAddress(), null), NOW)));
        given(forms.findByAttachmentIndividualRequestIdAndAttachmentParticipantIdAndDocTypeAndAttachmentDeletedAtIsNullOrderByCreatedAtAscIdAsc(
                Tasks.REQUEST, Tasks.SPOUSE, PrintedFormType.MEDICAL_CERTIFICATE))
                .willReturn(List.of(certificate(FIRST_DOCUMENT, JPEG), certificate(SECOND_DOCUMENT, PDF)));
    }

    @Test
    void facts_carryTheIdentifiersTheMomentOfAcceptanceBothPartiesAndTheCertificatesOfTheInsuredInOrder() {
        SubmissionFacts gathered = facts.gather(contract, IDENTIFIERS);

        assertThat(gathered.identifiers()).isEqualTo(IDENTIFIERS);
        assertThat(gathered.acceptedAt()).isEqualTo(ACCEPTED_AT);
        assertThat(gathered.policyholder()).isEqualTo(PersonSnapshots.of(Tasks.insured("0.00")));
        assertThat(gathered.insured()).isEqualTo(PersonSnapshots.of(Tasks.spouse("34000.00")));
        assertThat(gathered.certificates()).containsExactly(new SubmittedCertificate(FIRST_DOCUMENT, PrintedFormType.MEDICAL_CERTIFICATE, JPEG),
                new SubmittedCertificate(SECOND_DOCUMENT, PrintedFormType.MEDICAL_CERTIFICATE, PDF));
    }

    @Test
    void partiesStoredInAnotherOrder_areStillTakenByRole() {
        given(persons.findByContractIdOrderByRole(CONTRACT_ID)).willReturn(List.of(
                person(PersonRole.INSURED, PersonSnapshots.of(Tasks.spouse("34000.00"))),
                person(PersonRole.POLICYHOLDER, PersonSnapshots.of(Tasks.insured("0.00")))));

        SubmissionFacts gathered = facts.gather(contract, IDENTIFIERS);

        assertThat(gathered.policyholder().participantId()).isEqualTo(Tasks.INSURED);
        assertThat(gathered.insured().participantId()).isEqualTo(Tasks.SPOUSE);
    }

    @Test
    void contractWithoutCertificates_hasAnEmptyListOfThem() {
        given(forms.findByAttachmentIndividualRequestIdAndAttachmentParticipantIdAndDocTypeAndAttachmentDeletedAtIsNullOrderByCreatedAtAscIdAsc(
                Tasks.REQUEST, Tasks.SPOUSE, PrintedFormType.MEDICAL_CERTIFICATE)).willReturn(List.of());

        assertThat(facts.gather(contract, IDENTIFIERS).certificates()).isEmpty();
    }

    @Test
    void missingParty_isIllegalStateNamingTheContractAndTheRoleButNoPersonalData() {
        given(persons.findByContractIdOrderByRole(CONTRACT_ID)).willReturn(List.of(
                person(PersonRole.INSURED, PersonSnapshots.of(Tasks.spouse("34000.00")))));

        assertThatThrownBy(() -> facts.gather(contract, IDENTIFIERS)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(CONTRACT_ID.toString()).hasMessageContaining("POLICYHOLDER")
                .hasMessageNotContaining("Тестов");
    }

    @Test
    void missingAcceptance_isIllegalState() {
        given(acceptances.findByContractId(CONTRACT_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> facts.gather(contract, IDENTIFIERS)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(CONTRACT_ID.toString()).hasMessageContaining("акцепта");
    }

    @Test
    void certificateRecordWithoutAnObject_isIllegalState() {
        PrintedForm form = mock();
        given(form.id()).willReturn(FIRST_DOCUMENT);
        given(form.docType()).willReturn(PrintedFormType.MEDICAL_CERTIFICATE);
        given(form.attachment()).willReturn(Optional.of(
                DocumentAttachments.certificate(Tasks.REQUEST, Tasks.SPOUSE, UUID.randomUUID())));
        given(form.storedFile()).willReturn(Optional.empty());
        given(forms.findByAttachmentIndividualRequestIdAndAttachmentParticipantIdAndDocTypeAndAttachmentDeletedAtIsNullOrderByCreatedAtAscIdAsc(
                Tasks.REQUEST, Tasks.SPOUSE, PrintedFormType.MEDICAL_CERTIFICATE)).willReturn(List.of(form));

        assertThatThrownBy(() -> facts.gather(contract, IDENTIFIERS)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(FIRST_DOCUMENT.toString());
    }

    private static ContractPerson person(PersonRole role, ru.projectx.contracts.entity.party.PersonSnapshot person) {
        return ContractPerson.snapshot(PersonOwner.contract(CONTRACT_ID), role, person, NOW);
    }

    private static PrintedForm certificate(UUID documentId, StoredFile file) {
        PrintedForm form = PrintedForm.receivedAttachment(PrintedFormType.MEDICAL_CERTIFICATE,
                DocumentAttachments.certificate(Tasks.REQUEST, Tasks.SPOUSE, UUID.randomUUID()), file, NOW);
        ReflectionTestUtils.setField(form, "id", documentId);
        return form;
    }
}
