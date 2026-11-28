package ru.projectx.contracts.service.storage;

import java.util.Objects;
import ru.projectx.contracts.dto.AttachmentResponse;
import ru.projectx.contracts.dto.ConsentResponse;
import ru.projectx.contracts.entity.document.DocumentAttachment;
import ru.projectx.contracts.entity.document.PrintedForm;
import ru.projectx.contracts.entity.document.StoredFile;

final class AttachmentResponses {

    private AttachmentResponses() {
    }

    static AttachmentResponse certificate(PrintedForm document) {
        DocumentAttachment owner = attachmentOf(document);
        StoredFile file = storedFileOf(document);
        return new AttachmentResponse(
                document.id(),
                Objects.requireNonNull(owner.attachmentId()),
                Objects.requireNonNull(owner.individualRequestId()),
                Objects.requireNonNull(owner.participantId()),
                file.contentType(),
                file.size(),
                file.hash(),
                document.createdAt());
    }

    static ConsentResponse consent(PrintedForm document) {
        DocumentAttachment owner = attachmentOf(document);
        StoredFile file = storedFileOf(document);
        return new ConsentResponse(
                document.id(),
                Objects.requireNonNull(owner.attachmentId()),
                Objects.requireNonNull(owner.policyId()),
                Objects.requireNonNull(owner.familyMemberId()),
                file.contentType(),
                file.size(),
                file.hash(),
                document.createdAt());
    }

    private static DocumentAttachment attachmentOf(PrintedForm document) {
        return document.attachment().orElseThrow(
                () -> new IllegalStateException("документ " + document.id() + " — не вложение"));
    }

    private static StoredFile storedFileOf(PrintedForm document) {
        return document.storedFile().orElseThrow(
                () -> new IllegalStateException("у вложения " + document.id() + " нет файла"));
    }
}
