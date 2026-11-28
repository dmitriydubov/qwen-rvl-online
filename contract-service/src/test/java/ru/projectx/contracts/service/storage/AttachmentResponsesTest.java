package ru.projectx.contracts.service.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import ru.projectx.contracts.dto.AttachmentResponse;
import ru.projectx.contracts.dto.ConsentResponse;
import ru.projectx.contracts.entity.document.DocumentAttachments;
import ru.projectx.contracts.entity.document.PrintedForm;
import ru.projectx.contracts.entity.document.PrintedFormType;
import ru.projectx.contracts.entity.document.StoredFile;
import ru.projectx.contracts.entity.document.UskDocument;
import ru.projectx.contracts.entity.party.PersonOwner;

class AttachmentResponsesTest {

    private static final Instant NOW = Instant.parse("2026-02-10T07:05:00Z");

    @Test
    void certificate_isAnsweredWithItsOwnerAndFile() {
        PrintedForm certificate = PrintedForm.receivedAttachment(PrintedFormType.MEDICAL_CERTIFICATE,
                DocumentAttachments.certificate(Uploads.REQUEST, Uploads.PARTICIPANT, Uploads.ATTACHMENT),
                new StoredFile("certificates/f/a.png", Uploads.CONTENT_HASH, 76, "image/png"), NOW);

        assertThat(AttachmentResponses.certificate(certificate)).isEqualTo(new AttachmentResponse(certificate.id(),
                Uploads.ATTACHMENT, Uploads.REQUEST, Uploads.PARTICIPANT, "image/png", 76, Uploads.CONTENT_HASH, NOW));
    }

    @Test
    void consent_isAnsweredWithItsPolicyFamilyMemberAndFile() {
        PrintedForm consent = PrintedForm.receivedAttachment(PrintedFormType.PD_CONSENT,
                DocumentAttachments.consent(Uploads.POLICY, Uploads.FAMILY_MEMBER, Uploads.ATTACHMENT),
                new StoredFile("certificates/f/a.pdf", Uploads.CONTENT_HASH, 76, "application/pdf"), NOW);

        assertThat(AttachmentResponses.consent(consent)).isEqualTo(new ConsentResponse(consent.id(),
                Uploads.ATTACHMENT, Uploads.POLICY, Uploads.FAMILY_MEMBER, "application/pdf", 76, Uploads.CONTENT_HASH,
                NOW));
    }

    @Test
    void policyForm_isNoAttachment() {
        PrintedForm form = PrintedForm.issuedFor(PersonOwner.lmPolicy(Uploads.PARTICIPANT),
                new UskDocument(PrintedFormType.POLICY, "https://s3.example.test/lm.pdf", 1L), NOW);

        assertThatThrownBy(() -> AttachmentResponses.certificate(form)).isInstanceOf(IllegalStateException.class)
                .hasMessageEndingWith("не вложение");
        assertThatThrownBy(() -> AttachmentResponses.consent(form)).isInstanceOf(IllegalStateException.class)
                .hasMessageEndingWith("не вложение");
    }

    @Test
    void attachmentWithoutFile_isNotAnswered() {
        PrintedForm certificate = mock();
        given(certificate.attachment()).willReturn(Optional.of(
                DocumentAttachments.certificate(Uploads.REQUEST, Uploads.PARTICIPANT, Uploads.ATTACHMENT)));
        given(certificate.storedFile()).willReturn(Optional.empty());

        assertThatThrownBy(() -> AttachmentResponses.certificate(certificate)).isInstanceOf(IllegalStateException.class)
                .hasMessageEndingWith("нет файла");
    }
}
