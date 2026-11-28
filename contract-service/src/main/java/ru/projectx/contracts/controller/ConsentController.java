package ru.projectx.contracts.controller;

import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;
import ru.projectx.contracts.dto.ConsentResponse;
import ru.projectx.contracts.service.storage.ConsentUpload;
import ru.projectx.contracts.service.storage.ConsentUploads;

@InternalApi
public class ConsentController {

    private final ConsentUploads uploads;

    public ConsentController(ConsentUploads uploads) {
        this.uploads = uploads;
    }

    @PostMapping(path = "/policies/{policy_id}/family-members/{family_member_id}/consents",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ConsentResponse upload(
            @PathVariable("policy_id") UUID policyId,
            @PathVariable("family_member_id") UUID familyMemberId,
            @RequestParam("attachment_id") UUID attachmentId,
            @RequestPart("file") MultipartFile file) {
        return uploads.receive(new ConsentUpload(policyId, familyMemberId, attachmentId,
                MultipartParts.requireFile(file)));
    }
}
