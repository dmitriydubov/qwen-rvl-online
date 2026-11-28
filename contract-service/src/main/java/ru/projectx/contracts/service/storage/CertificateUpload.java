package ru.projectx.contracts.service.storage;

import java.util.UUID;

public record CertificateUpload(
        UUID individualRequestId,
        UUID participantId,
        UUID attachmentId,
        UploadFile file) {
}
