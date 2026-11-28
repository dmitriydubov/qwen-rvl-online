package ru.projectx.contracts.service.storage;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import ru.projectx.contracts.dto.AttachmentResponse;
import ru.projectx.contracts.dto.ConsentResponse;
import ru.projectx.contracts.entity.document.DocumentAttachment;
import ru.projectx.contracts.entity.document.DocumentAttachments;
import ru.projectx.contracts.entity.document.PrintedForm;
import ru.projectx.contracts.entity.document.PrintedFormType;
import ru.projectx.contracts.entity.issuance.IssuanceRequest;
import ru.projectx.contracts.exception.RequestCancelledException;
import ru.projectx.contracts.repository.issuance.IssuanceRequestRepository;

final class AttachmentTarget<R> {

    private final UUID attachmentId;
    private final UploadFile file;
    private final PrintedFormType docType;
    private final DocumentAttachment owner;
    private final Consumer<IssuanceRequestRepository> stateCheck;
    private final Function<PrintedForm, R> response;

    private AttachmentTarget(UUID attachmentId, UploadFile file, PrintedFormType docType, DocumentAttachment owner,
            Consumer<IssuanceRequestRepository> stateCheck, Function<PrintedForm, R> response) {
        this.attachmentId = attachmentId;
        this.file = file;
        this.docType = docType;
        this.owner = owner;
        this.stateCheck = stateCheck;
        this.response = response;
    }

    static AttachmentTarget<AttachmentResponse> certificate(CertificateUpload upload) {
        return new AttachmentTarget<>(
                upload.attachmentId(),
                upload.file(),
                PrintedFormType.MEDICAL_CERTIFICATE,
                DocumentAttachments.certificate(upload.individualRequestId(), upload.participantId(),
                        upload.attachmentId()),
                requests -> rejectIfCancelled(requests, upload.individualRequestId()),
                AttachmentResponses::certificate);
    }

    static AttachmentTarget<ConsentResponse> consent(ConsentUpload upload) {
        return new AttachmentTarget<>(
                upload.attachmentId(),
                upload.file(),
                PrintedFormType.PD_CONSENT,
                DocumentAttachments.consent(upload.policyId(), upload.familyMemberId(), upload.attachmentId()),
                requests -> { },
                AttachmentResponses::consent);
    }

    UUID attachmentId() {
        return attachmentId;
    }

    UploadFile file() {
        return file;
    }

    PrintedFormType docType() {
        return docType;
    }

    DocumentAttachment owner() {
        return owner;
    }

    void checkOpen(IssuanceRequestRepository requests) {
        stateCheck.accept(requests);
    }

    R response(PrintedForm form) {
        return response.apply(form);
    }

    private static void rejectIfCancelled(IssuanceRequestRepository requests, UUID individualRequestId) {
        requests.findById(individualRequestId)
                .filter(IssuanceRequest::cancelled)
                .ifPresent(cancelled -> {
                    throw new RequestCancelledException(individualRequestId);
                });
    }
}
