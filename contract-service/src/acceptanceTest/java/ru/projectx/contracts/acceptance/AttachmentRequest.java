package ru.projectx.contracts.acceptance;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

record AttachmentRequest(
        String path,
        Optional<AttachmentPart> attachmentPart,
        Optional<FilePart> filePart,
        Optional<String> requestId,
        boolean multipart) {

    static final String BOUNDARY = "stand-attachment-boundary";

    record AttachmentPart(String value, @Nullable String fileName) {
    }

    record FilePart(@Nullable String fileName, @Nullable String contentType, byte[] content) {
    }

    AttachmentRequest withRequestId(String value) {
        return new AttachmentRequest(path, attachmentPart, filePart, Optional.of(value), multipart);
    }

    AttachmentRequest withPath(String newPath) {
        return new AttachmentRequest(newPath, attachmentPart, filePart, requestId, multipart);
    }

    AttachmentRequest withAttachment(Optional<AttachmentPart> newAttachment) {
        return new AttachmentRequest(path, newAttachment, filePart, requestId, multipart);
    }

    AttachmentRequest withFile(Optional<FilePart> newFile) {
        return new AttachmentRequest(path, attachmentPart, newFile, requestId, multipart);
    }

    AttachmentRequest asJson() {
        return new AttachmentRequest(path, attachmentPart, Optional.empty(), requestId, false);
    }

    String contentType() {
        return multipart ? "multipart/form-data; boundary=" + BOUNDARY : "application/json";
    }

    byte[] body() {
        if (!multipart) {
            return ("{\"attachment_id\":\"" + attachmentPart.map(AttachmentPart::value).orElse("") + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        attachmentPart.ifPresent(part -> attachment(body, part));
        filePart.ifPresent(file -> file(body, file));
        text(body, "--" + BOUNDARY + "--\r\n");
        return body.toByteArray();
    }

    private static void attachment(ByteArrayOutputStream body, AttachmentPart part) {
        String filename = part.fileName() == null ? "" : "; filename=\"" + part.fileName() + "\"";
        text(body, "--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"attachment_id\""
                + filename + "\r\n\r\n" + part.value() + "\r\n");
    }

    private static void file(ByteArrayOutputStream body, FilePart file) {
        String filename = file.fileName() == null ? "" : "; filename=\"" + file.fileName() + "\"";
        text(body, "--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"file\"" + filename + "\r\n"
                + Optional.ofNullable(file.contentType()).map(type -> "Content-Type: " + type + "\r\n").orElse("")
                + "\r\n");
        body.writeBytes(file.content());
        text(body, "\r\n");
    }

    private static void text(ByteArrayOutputStream body, String text) {
        body.writeBytes(text.getBytes(StandardCharsets.UTF_8));
    }
}
