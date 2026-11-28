package ru.projectx.contracts.service.km.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import ru.projectx.contracts.entity.contract.Contract;
import ru.projectx.contracts.entity.document.DocumentAttachment;
import ru.projectx.contracts.entity.document.DocumentAttachments;
import ru.projectx.contracts.entity.document.PrintedForm;
import ru.projectx.contracts.entity.document.PrintedFormType;
import ru.projectx.contracts.entity.document.StoredFile;
import ru.projectx.contracts.exception.CertificateKeyMissingException;
import ru.projectx.contracts.exception.CertificateMissingException;
import ru.projectx.contracts.exception.StorageUnavailableException;
import ru.projectx.contracts.repository.issuance.PrintedFormRepository;
import ru.projectx.contracts.service.storage.AttachmentMetrics;
import ru.projectx.contracts.service.storage.CertificateStore;
import ru.projectx.contracts.service.usk.request.Prerequisites;
import ru.projectx.contracts.support.Issuances;
import ru.projectx.contracts.support.Tasks;

class KmContractCertificatesTest {

    private static final Instant NOW = Instant.parse("2026-02-10T07:32:00Z");
    private static final StoredFile JPEG = new StoredFile("1111111111_222222222/certificates/0b6c/3c2f.jpg",
            "1".repeat(64), 76L, "image/jpeg");
    private static final StoredFile PDF = new StoredFile("certificates/0b6c/1a4c.pdf", "2".repeat(64), 20000L,
            "application/pdf");

    private final PrintedFormRepository forms = mock();
    private final CertificateStore store = mock();
    private final AttachmentMetrics metrics = mock();
    private final KmContractCertificates certificates = new KmContractCertificates(
            new InsuredCertificates(forms), store, metrics);
    private final Contract contract = Issuances.contract("0f4b8e2a-6c13-4d7e-b9a1-2e5c7d3f8a64", Tasks.SPOUSE,
            "DKM-2026-000001");

    @BeforeEach
    void insuredHasTwoCertificates() {
        given(forms.findByAttachmentIndividualRequestIdAndAttachmentParticipantIdAndDocTypeAndAttachmentDeletedAtIsNullOrderByCreatedAtAscIdAsc(
                Tasks.REQUEST, Tasks.SPOUSE, PrintedFormType.MEDICAL_CERTIFICATE))
                .willReturn(List.of(certificate(JPEG), certificate(PDF)));
    }

    @Test
    void everyCertificateOfTheInsuredInTheStorage_meetsThePrerequisites() {
        assertThat(certificates.check(contract)).isEqualTo(new Prerequisites.Met());

        InOrder order = inOrder(store);
        order.verify(store).verifyPresent(JPEG.key());
        order.verify(store).verifyPresent(PDF.key());
        verifyNoInteractions(metrics);
    }

    @Test
    void contractWithoutCertificates_meetsThePrerequisitesWithoutAskingTheStorage() {
        given(forms.findByAttachmentIndividualRequestIdAndAttachmentParticipantIdAndDocTypeAndAttachmentDeletedAtIsNullOrderByCreatedAtAscIdAsc(
                Tasks.REQUEST, Tasks.SPOUSE, PrintedFormType.MEDICAL_CERTIFICATE)).willReturn(List.of());

        assertThat(certificates.check(contract)).isEqualTo(new Prerequisites.Met());

        verifyNoInteractions(store);
    }

    @Test
    void deletedCertificatesAndConsents_areExcludedByInsuredCertificates() {
        PrintedForm deleted = PrintedForm.receivedAttachment(PrintedFormType.MEDICAL_CERTIFICATE,
                new DocumentAttachment(Tasks.REQUEST, Tasks.SPOUSE, null, null, UUID.randomUUID(), NOW, "req-1"),
                JPEG, NOW);
        PrintedForm consent = PrintedForm.receivedAttachment(PrintedFormType.PD_CONSENT,
                DocumentAttachments.consent(UUID.randomUUID(), Tasks.SPOUSE, UUID.randomUUID()), PDF, NOW);
        PrintedForm active = certificate(PDF);
        given(forms.findByAttachmentIndividualRequestIdAndAttachmentParticipantIdAndDocTypeAndAttachmentDeletedAtIsNullOrderByCreatedAtAscIdAsc(
                Tasks.REQUEST, Tasks.SPOUSE, PrintedFormType.MEDICAL_CERTIFICATE))
                .willReturn(List.of(deleted, consent, active));

        List<SubmittedCertificate> selected = new InsuredCertificates(forms).of(contract);

        assertThat(selected).singleElement()
                .returns(PrintedFormType.MEDICAL_CERTIFICATE, SubmittedCertificate::docType)
                .returns(PDF, SubmittedCertificate::file);
    }

    @Test
    void unavailableStorage_postponesTheSubmissionAndIsCounted() {
        willThrow(new StorageUnavailableException(new IOException("Connection refused"))).given(store)
                .verifyPresent(JPEG.key());

        assertThat(certificates.check(contract)).isEqualTo(new Prerequisites.Postponed(
                "хранилище справок недоступно, передача отложена до следующего прохода"));

        verify(store, never()).verifyPresent(PDF.key());
        verify(metrics).countStorageUnavailable();
    }

    @Test
    void missingFile_breaksThePrerequisitesWithItsReasonAndFailure() {
        CertificateMissingException missing = new CertificateMissingException(new IllegalStateException("NoSuchKey"));
        willThrow(missing).given(store).verifyPresent(PDF.key());

        assertThat(certificates.check(contract)).isEqualTo(new Prerequisites.Broken(
                "передача договора КМ не подготовлена: файла справки нет в хранилище, договор переведён в ERROR",
                missing));

        verifyNoInteractions(metrics);
    }

    @Test
    void certificateRecordWithoutAnObject_breaksThePrerequisitesAsAnInternalFailureWithoutAskingTheStorage() {
        PrintedForm withoutObject = mock();
        given(withoutObject.docType()).willReturn(PrintedFormType.MEDICAL_CERTIFICATE);
        given(withoutObject.attachment()).willReturn(Optional.of(
                DocumentAttachments.certificate(Tasks.REQUEST, Tasks.SPOUSE, UUID.randomUUID())));
        given(withoutObject.storedFile()).willReturn(Optional.empty());
        given(forms.findByAttachmentIndividualRequestIdAndAttachmentParticipantIdAndDocTypeAndAttachmentDeletedAtIsNullOrderByCreatedAtAscIdAsc(
                Tasks.REQUEST, Tasks.SPOUSE, PrintedFormType.MEDICAL_CERTIFICATE)).willReturn(List.of(withoutObject));

        assertThat(certificates.check(contract)).isInstanceOfSatisfying(Prerequisites.Broken.class, broken -> {
            assertThat(broken.reason())
                    .isEqualTo("передача договора КМ не подготовлена из-за внутренней ошибки, договор переведён в ERROR");
            assertThat(broken.failure()).isInstanceOf(CertificateKeyMissingException.class);
        });

        verifyNoInteractions(store, metrics);
    }

    @Test
    void unexpectedFailureOfTheStorageCheck_isNotTakenForABrokenPrerequisite() {
        IllegalStateException unexpected = new IllegalStateException("непредвиденный отказ");
        willThrow(unexpected).given(store).verifyPresent(JPEG.key());

        assertThatThrownBy(() -> certificates.check(contract)).isSameAs(unexpected);
    }

    private static PrintedForm certificate(StoredFile file) {
        return PrintedForm.receivedAttachment(PrintedFormType.MEDICAL_CERTIFICATE,
                DocumentAttachments.certificate(Tasks.REQUEST, Tasks.SPOUSE, UUID.randomUUID()), file, NOW);
    }
}
