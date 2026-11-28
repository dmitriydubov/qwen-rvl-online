package ru.projectx.contracts.service.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.projectx.contracts.dto.AttachmentResponse;
import ru.projectx.contracts.dto.ConsentResponse;
import ru.projectx.contracts.entity.document.DocumentAttachment;
import ru.projectx.contracts.entity.document.DocumentAttachments;
import ru.projectx.contracts.entity.document.PrintedForm;
import ru.projectx.contracts.entity.document.PrintedFormType;
import ru.projectx.contracts.entity.document.StoredFile;
import ru.projectx.contracts.entity.issuance.DocumentKind;
import ru.projectx.contracts.entity.issuance.IssuanceRequest;
import ru.projectx.contracts.entity.issuance.RequestParticipant;
import ru.projectx.contracts.exception.AttachmentConflictException;
import ru.projectx.contracts.exception.RequestCancelledException;
import ru.projectx.contracts.repository.issuance.IssuanceRequestRepository;
import ru.projectx.contracts.repository.issuance.PrintedFormRepository;

class AttachmentRegistryTest {

    private static final Instant NOW = Instant.parse("2026-02-10T07:05:00Z");
    private static final StoredFile FILE = new StoredFile("certificates/f/" + Uploads.ATTACHMENT + ".jpg",
            Uploads.CONTENT_HASH, 76, "image/jpeg");

    private final IssuanceRequestRepository requests = mock(IssuanceRequestRepository.class);
    private final PrintedFormRepository documents = mock(PrintedFormRepository.class);
    private final AttachmentRegistry registry = new AttachmentRegistry(requests, documents,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void certificateBeforeFirstTask_hasNoAcceptedRecordAndCanBeRegistered() {
        when(requests.findById(Uploads.REQUEST)).thenReturn(Optional.empty());
        when(documents.findByAttachmentAttachmentId(Uploads.ATTACHMENT)).thenReturn(Optional.empty());
        when(documents.saveAndFlush(any(PrintedForm.class))).thenAnswer(saved -> saved.getArgument(0));
        AttachmentTarget<AttachmentResponse> target = AttachmentTarget.certificate(Uploads.jpeg());

        assertThat(registry.accepted(target, Uploads.CONTENT_HASH)).isEmpty();
        AttachmentResponse response = registry.register(target, FILE);

        assertThat(response.individualRequestId()).isEqualTo(Uploads.REQUEST);
        assertThat(response.participantId()).isEqualTo(Uploads.PARTICIPANT);
        assertThat(response.createdAt()).isEqualTo(NOW);
    }

    @Test
    void certificateForParticipantOutsideCurrentTask_isAcceptedAndRegistered() {
        knownRequest(false, Uploads.OTHER_PARTICIPANT);
        when(documents.findByAttachmentAttachmentId(Uploads.ATTACHMENT)).thenReturn(Optional.empty());
        when(documents.saveAndFlush(any(PrintedForm.class))).thenAnswer(saved -> saved.getArgument(0));
        AttachmentTarget<AttachmentResponse> target = AttachmentTarget.certificate(Uploads.jpeg());

        assertThat(registry.accepted(target, Uploads.CONTENT_HASH)).isEmpty();
        assertThat(registry.register(target, FILE).participantId()).isEqualTo(Uploads.PARTICIPANT);
    }

    @Test
    void cancelledRequest_rejectsCertificateEvenWithoutParticipantsOrOnRepeat() {
        IssuanceRequest cancelledEarly = IssuanceRequest.start(Uploads.REQUEST, NOW);
        cancelledEarly.cancel(NOW);
        when(requests.findById(Uploads.REQUEST)).thenReturn(Optional.of(cancelledEarly));
        when(documents.findByAttachmentAttachmentId(Uploads.ATTACHMENT))
                .thenReturn(Optional.of(certificate(Uploads.REQUEST, Uploads.PARTICIPANT, Uploads.CONTENT_HASH)));
        AttachmentTarget<AttachmentResponse> target = AttachmentTarget.certificate(Uploads.jpeg());

        assertThatThrownBy(() -> registry.accepted(target, Uploads.CONTENT_HASH))
                .isInstanceOf(RequestCancelledException.class).hasMessageContaining(Uploads.REQUEST.toString());
        verifyNoInteractions(documents);
    }

    @Test
    void sameCertificateWithSameContent_isThePriorRecord() {
        when(requests.findById(Uploads.REQUEST)).thenReturn(Optional.empty());
        PrintedForm prior = certificate(Uploads.REQUEST, Uploads.PARTICIPANT, Uploads.CONTENT_HASH);
        when(documents.findByAttachmentAttachmentId(Uploads.ATTACHMENT)).thenReturn(Optional.of(prior));

        assertThat(registry.accepted(AttachmentTarget.certificate(Uploads.jpeg()), Uploads.CONTENT_HASH)).get()
                .returns(Uploads.ATTACHMENT, AttachmentResponse::attachmentId)
                .returns(NOW.minusSeconds(300), AttachmentResponse::createdAt);
    }

    @Test
    void sameConsentWithSameContent_isThePriorRecordWithoutQueryingRequests() {
        PrintedForm prior = consent(Uploads.POLICY, Uploads.FAMILY_MEMBER, Uploads.CONTENT_HASH);
        when(documents.findByAttachmentAttachmentId(Uploads.ATTACHMENT)).thenReturn(Optional.of(prior));

        assertThat(registry.accepted(AttachmentTarget.consent(Uploads.consentPdf()), Uploads.CONTENT_HASH)).get()
                .returns(Uploads.ATTACHMENT, ConsentResponse::attachmentId)
                .returns(Uploads.POLICY, ConsentResponse::policyId)
                .returns(Uploads.FAMILY_MEMBER, ConsentResponse::familyMemberId)
                .returns(NOW.minusSeconds(300), ConsentResponse::createdAt);
        verifyNoInteractions(requests);
    }

    @Test
    void sameAttachmentWithOtherContent_isConflict() {
        when(requests.findById(Uploads.REQUEST)).thenReturn(Optional.empty());
        when(documents.findByAttachmentAttachmentId(Uploads.ATTACHMENT))
                .thenReturn(Optional.of(certificate(Uploads.REQUEST, Uploads.PARTICIPANT, "0".repeat(64))));

        assertThatThrownBy(() -> registry.accepted(AttachmentTarget.certificate(Uploads.jpeg()), Uploads.CONTENT_HASH))
                .isInstanceOf(AttachmentConflictException.class).hasMessageContaining(Uploads.ATTACHMENT.toString());
    }

    @Test
    void sameAttachmentOfAnotherParticipantOrAnotherRequest_isConflict() {
        when(requests.findById(Uploads.REQUEST)).thenReturn(Optional.empty());
        when(documents.findByAttachmentAttachmentId(Uploads.ATTACHMENT))
                .thenReturn(Optional.of(certificate(Uploads.REQUEST, Uploads.OTHER_PARTICIPANT, Uploads.CONTENT_HASH)));

        assertThatThrownBy(() -> registry.accepted(AttachmentTarget.certificate(Uploads.jpeg()), Uploads.CONTENT_HASH))
                .isInstanceOf(AttachmentConflictException.class);

        when(documents.findByAttachmentAttachmentId(Uploads.ATTACHMENT))
                .thenReturn(Optional.of(certificate(Uploads.OTHER_REQUEST, Uploads.PARTICIPANT, Uploads.CONTENT_HASH)));

        assertThatThrownBy(() -> registry.accepted(AttachmentTarget.certificate(Uploads.jpeg()), Uploads.CONTENT_HASH))
                .isInstanceOf(AttachmentConflictException.class);
    }

    @Test
    void sameConsentOfAnotherPolicyOrFamilyMember_isConflict() {
        when(documents.findByAttachmentAttachmentId(Uploads.ATTACHMENT))
                .thenReturn(Optional.of(consent(Uploads.OTHER_POLICY, Uploads.FAMILY_MEMBER, Uploads.CONTENT_HASH)));

        assertThatThrownBy(() -> registry.accepted(AttachmentTarget.consent(Uploads.consentPdf()), Uploads.CONTENT_HASH))
                .isInstanceOf(AttachmentConflictException.class);

        when(documents.findByAttachmentAttachmentId(Uploads.ATTACHMENT))
                .thenReturn(Optional.of(consent(Uploads.POLICY, Uploads.OTHER_FAMILY_MEMBER, Uploads.CONTENT_HASH)));

        assertThatThrownBy(() -> registry.accepted(AttachmentTarget.consent(Uploads.consentPdf()), Uploads.CONTENT_HASH))
                .isInstanceOf(AttachmentConflictException.class);
    }

    @Test
    void sameAttachmentAcrossCertificateAndConsent_isConflictInBothDirections() {
        when(requests.findById(Uploads.REQUEST)).thenReturn(Optional.empty());
        when(documents.findByAttachmentAttachmentId(Uploads.ATTACHMENT))
                .thenReturn(Optional.of(certificate(Uploads.REQUEST, Uploads.PARTICIPANT, Uploads.CONTENT_HASH)));

        assertThatThrownBy(() -> registry.accepted(AttachmentTarget.consent(Uploads.consentPdf()), Uploads.CONTENT_HASH))
                .isInstanceOf(AttachmentConflictException.class);

        when(documents.findByAttachmentAttachmentId(Uploads.ATTACHMENT))
                .thenReturn(Optional.of(consent(Uploads.POLICY, Uploads.FAMILY_MEMBER, Uploads.CONTENT_HASH)));

        assertThatThrownBy(() -> registry.accepted(AttachmentTarget.certificate(Uploads.jpeg()), Uploads.CONTENT_HASH))
                .isInstanceOf(AttachmentConflictException.class);
    }

    @Test
    void sameAttachmentWhenMarkedDeleted_isConflictForBothCertificateAndConsent() {
        when(requests.findById(Uploads.REQUEST)).thenReturn(Optional.empty());
        PrintedForm deletedCert = PrintedForm.receivedAttachment(PrintedFormType.MEDICAL_CERTIFICATE,
                new DocumentAttachment(Uploads.REQUEST, Uploads.PARTICIPANT, null, null, Uploads.ATTACHMENT, NOW, "req-1"),
                FILE, NOW.minusSeconds(300));
        when(documents.findByAttachmentAttachmentId(Uploads.ATTACHMENT)).thenReturn(Optional.of(deletedCert));

        assertThatThrownBy(() -> registry.accepted(AttachmentTarget.certificate(Uploads.jpeg()), Uploads.CONTENT_HASH))
                .isInstanceOf(AttachmentConflictException.class);

        PrintedForm deletedConsent = PrintedForm.receivedAttachment(PrintedFormType.PD_CONSENT,
                new DocumentAttachment(null, null, Uploads.POLICY, Uploads.FAMILY_MEMBER, Uploads.ATTACHMENT, NOW, "req-2"),
                FILE, NOW.minusSeconds(300));
        when(documents.findByAttachmentAttachmentId(Uploads.ATTACHMENT)).thenReturn(Optional.of(deletedConsent));

        assertThatThrownBy(() -> registry.accepted(AttachmentTarget.consent(Uploads.consentPdf()), Uploads.CONTENT_HASH))
                .isInstanceOf(AttachmentConflictException.class);
    }

    @Test
    void sameOwnerWithMismatchedDocType_isConflict() {
        when(requests.findById(Uploads.REQUEST)).thenReturn(Optional.empty());
        PrintedForm wrongType = PrintedForm.receivedAttachment(PrintedFormType.PD_CONSENT,
                DocumentAttachments.certificate(Uploads.REQUEST, Uploads.PARTICIPANT, Uploads.ATTACHMENT), FILE,
                NOW.minusSeconds(300));
        when(documents.findByAttachmentAttachmentId(Uploads.ATTACHMENT)).thenReturn(Optional.of(wrongType));

        assertThatThrownBy(() -> registry.accepted(AttachmentTarget.certificate(Uploads.jpeg()), Uploads.CONTENT_HASH))
                .isInstanceOf(AttachmentConflictException.class);
    }

    @Test
    void register_savesCertificateAndConsentAtTheCurrentInstant() {
        knownRequest(false, Uploads.PARTICIPANT);
        when(documents.saveAndFlush(any(PrintedForm.class))).thenAnswer(saved -> saved.getArgument(0));

        AttachmentResponse certResponse = registry.register(AttachmentTarget.certificate(Uploads.jpeg()), FILE);
        ConsentResponse consentResponse = registry.register(AttachmentTarget.consent(Uploads.consentPdf()), FILE);

        ArgumentCaptor<PrintedForm> saved = ArgumentCaptor.forClass(PrintedForm.class);
        verify(documents, times(2)).saveAndFlush(saved.capture());
        PrintedForm savedCert = saved.getAllValues().get(0);
        assertThat(savedCert.docType()).isEqualTo(PrintedFormType.MEDICAL_CERTIFICATE);
        assertThat(savedCert.attachment())
                .contains(DocumentAttachments.certificate(Uploads.REQUEST, Uploads.PARTICIPANT, Uploads.ATTACHMENT));
        assertThat(savedCert.storedFile()).contains(FILE);
        assertThat(certResponse.fileHash()).isEqualTo(Uploads.CONTENT_HASH);
        assertThat(certResponse.createdAt()).isEqualTo(NOW);

        PrintedForm savedConsent = saved.getAllValues().get(1);
        assertThat(savedConsent.docType()).isEqualTo(PrintedFormType.PD_CONSENT);
        assertThat(savedConsent.attachment())
                .contains(DocumentAttachments.consent(Uploads.POLICY, Uploads.FAMILY_MEMBER, Uploads.ATTACHMENT));
        assertThat(consentResponse.policyId()).isEqualTo(Uploads.POLICY);
        assertThat(consentResponse.familyMemberId()).isEqualTo(Uploads.FAMILY_MEMBER);
        assertThat(consentResponse.createdAt()).isEqualTo(NOW);
    }

    @Test
    void requestCancelledWhileTheFileWasStored_isNotRegistered() {
        knownRequest(true, Uploads.PARTICIPANT);

        assertThatThrownBy(() -> registry.register(AttachmentTarget.certificate(Uploads.jpeg()), FILE))
                .isInstanceOf(RequestCancelledException.class);

        verify(documents, never()).saveAndFlush(any());
    }

    private void knownRequest(boolean cancelled, UUID... participants) {
        IssuanceRequest request = IssuanceRequest.start(Uploads.REQUEST, NOW);
        request.takeComposition(Arrays.stream(participants)
                .map(participant -> new RequestParticipant(participant, DocumentKind.LM_POLICY)).toList(), NOW);
        if (cancelled) {
            request.cancel(NOW);
        }
        when(requests.findById(Uploads.REQUEST)).thenReturn(Optional.of(request));
    }

    private static PrintedForm certificate(UUID request, UUID participant, String hash) {
        return PrintedForm.receivedAttachment(PrintedFormType.MEDICAL_CERTIFICATE,
                DocumentAttachments.certificate(request, participant, Uploads.ATTACHMENT),
                new StoredFile(FILE.key(), hash, FILE.size(), FILE.contentType()), NOW.minusSeconds(300));
    }

    private static PrintedForm consent(UUID policy, UUID familyMember, String hash) {
        return PrintedForm.receivedAttachment(PrintedFormType.PD_CONSENT,
                DocumentAttachments.consent(policy, familyMember, Uploads.ATTACHMENT),
                new StoredFile(FILE.key(), hash, FILE.size(), FILE.contentType()), NOW.minusSeconds(300));
    }
}
