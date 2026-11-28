package ru.projectx.contracts.service.storage;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

final class Uploads {

    static final UUID REQUEST = UUID.fromString("61a7982c-a0b4-4bbf-9527-60b40b752803");
    static final UUID OTHER_REQUEST = UUID.fromString("7f2c9e4a-1b3d-4e5f-8a6b-9c0d1e2f3a4b");
    static final UUID PARTICIPANT = UUID.fromString("6778e8c2-985a-4d54-9bd3-0f6403b104d6");
    static final UUID OTHER_PARTICIPANT = UUID.fromString("9275471c-d2fe-484f-97a6-d6d7b1267b5e");
    static final UUID POLICY = UUID.fromString("a23ba061-f742-4b20-8b95-36a0e525c087");
    static final UUID OTHER_POLICY = UUID.fromString("b34cb172-0853-4c31-9c06-47b1f636d198");
    static final UUID FAMILY_MEMBER = UUID.fromString("c45dc283-1964-4d42-8d17-58c2a747e209");
    static final UUID OTHER_FAMILY_MEMBER = UUID.fromString("d56ed394-2a75-4e53-9e28-69d3b858f310");
    static final UUID ATTACHMENT = UUID.fromString("3c2f8e10-5a7b-4d21-9f6e-1b0a7c4d8e21");
    static final String CONTENT = "СТЕНД: вымышленная справка 070/у, участник 1";
    static final String CONTENT_HASH = "7851d78af0fb98bbbda8933f49860a009ac7761c21a1136613c8a6704b156251";

    private Uploads() {
    }

    static CertificateUpload jpeg() {
        return of(PARTICIPANT, "image/jpeg", CONTENT);
    }

    static CertificateUpload of(UUID participant, @Nullable String contentType, String content) {
        return new CertificateUpload(REQUEST, participant, ATTACHMENT, file(contentType, content));
    }

    static ConsentUpload consentPdf() {
        return consent(POLICY, FAMILY_MEMBER, "application/pdf", CONTENT);
    }

    static ConsentUpload consent(UUID policy, UUID familyMember, @Nullable String contentType, String content) {
        return new ConsentUpload(policy, familyMember, ATTACHMENT, file(contentType, content));
    }

    static UploadFile file(@Nullable String contentType, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return new UploadFile(contentType, bytes.length, () -> new ByteArrayInputStream(bytes));
    }

    static UploadFile sized(@Nullable String contentType, long size) {
        return new UploadFile(contentType, size, () -> new ByteArrayInputStream(new byte[0]));
    }
}
