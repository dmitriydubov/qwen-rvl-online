package ru.projectx.contracts.dto;

import java.time.Instant;
import java.util.UUID;

public record ConsentResponse(
        UUID documentId,
        UUID attachmentId,
        UUID policyId,
        UUID familyMemberId,
        String contentType,
        long fileSize,
        String fileHash,
        Instant createdAt) {
}
