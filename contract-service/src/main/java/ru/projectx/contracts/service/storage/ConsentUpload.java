package ru.projectx.contracts.service.storage;

import java.util.UUID;

public record ConsentUpload(
        UUID policyId,
        UUID familyMemberId,
        UUID attachmentId,
        UploadFile file) {
}
