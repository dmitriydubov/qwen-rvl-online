package ru.projectx.contracts.entity.document;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Embeddable
public record DocumentAttachment(
        @Nullable @Column(name = "individual_request_id", updatable = false) UUID individualRequestId,
        @Nullable @Column(name = "participant_id", updatable = false) UUID participantId,
        @Nullable @Column(name = "policy_id", updatable = false) UUID policyId,
        @Nullable @Column(name = "family_member_id", updatable = false) UUID familyMemberId,
        @Nullable @Column(name = "attachment_id", updatable = false) UUID attachmentId,
        @Nullable @Column(name = "deleted_at") Instant deletedAt,
        @Nullable @Column(name = "deleted_request_id") String deletedRequestId) {
}
