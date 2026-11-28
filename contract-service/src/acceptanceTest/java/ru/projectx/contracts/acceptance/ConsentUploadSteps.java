package ru.projectx.contracts.acceptance;

import io.cucumber.datatable.DataTable;
import io.cucumber.java.ru.Когда;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

public class ConsentUploadSteps {

    private static final String ATTACHMENT = "4d3a9f21-6b8c-4e32-8a7f-2c1b8d5e9f32";
    private static final String FILE_NAME = "Тестова_Ольга_согласие.pdf";
    private static final String CONTENT = "СТЕНД: вымышленное согласие на обработку ПДн, родственник 1";
    private static final String PDF = "application/pdf";
    private static final String NOT_A_GUID = "not-a-guid";
    private static final byte[] FILLER = "pdcn".getBytes(StandardCharsets.US_ASCII);
    private static final int OVER_LIMIT = 5_242_881;

    private static final Map<String, BiFunction<String, String, AttachmentRequest>> DEFECTS = Map.ofEntries(
            Map.entry("тип файла image/tiff",
                    (p, f) -> AttachmentUploadSteps.withFile(standard(p, f), FILE_NAME, "image/tiff",
                            AttachmentUploadSteps.content(CONTENT))),
            Map.entry("тип файла image/gif",
                    (p, f) -> AttachmentUploadSteps.withFile(standard(p, f), FILE_NAME, "image/gif",
                            AttachmentUploadSteps.content(CONTENT))),
            Map.entry("тип файла не указан",
                    (p, f) -> AttachmentUploadSteps.withFile(standard(p, f), FILE_NAME, null,
                            AttachmentUploadSteps.content(CONTENT))),
            Map.entry("пустой файл",
                    (p, f) -> AttachmentUploadSteps.withFile(standard(p, f), FILE_NAME, PDF, new byte[0])),
            Map.entry("файл на байт больше предела",
                    (p, f) -> AttachmentUploadSteps.withFile(standard(p, f), FILE_NAME, PDF,
                            AttachmentUploadSteps.repeat(FILLER, OVER_LIMIT))),
            Map.entry("идентификатор полиса в пути — не GUID", (p, f) -> standard(NOT_A_GUID, f)),
            Map.entry("идентификатор родственника в пути — не GUID", (p, f) -> standard(p, NOT_A_GUID)),
            Map.entry("нет части attachment_id", (p, f) -> standard(p, f).withAttachment(Optional.empty())),
            Map.entry("attachment_id — не GUID",
                    (p, f) -> standard(p, f).withAttachment(Optional.of(new AttachmentRequest.AttachmentPart(NOT_A_GUID, null)))),
            Map.entry("часть attachment_id передана с непустым filename",
                    (p, f) -> standard(p, f).withAttachment(Optional.of(new AttachmentRequest.AttachmentPart(ATTACHMENT, "id.txt")))),
            Map.entry("часть attachment_id передана с пустым filename",
                    (p, f) -> standard(p, f).withAttachment(Optional.of(new AttachmentRequest.AttachmentPart(ATTACHMENT, "")))),
            Map.entry("нет части file", (p, f) -> standard(p, f).withFile(Optional.empty())),
            Map.entry("часть file без filename",
                    (p, f) -> AttachmentUploadSteps.withFile(standard(p, f), null, PDF,
                            AttachmentUploadSteps.content(CONTENT))),
            Map.entry("часть file с пустым filename",
                    (p, f) -> AttachmentUploadSteps.withFile(standard(p, f), "", PDF,
                            AttachmentUploadSteps.content(CONTENT))),
            Map.entry("часть file с пробельным filename",
                    (p, f) -> AttachmentUploadSteps.withFile(standard(p, f), "   ", PDF,
                            AttachmentUploadSteps.content(CONTENT))),
            Map.entry("тело не multipart", (p, f) -> standard(p, f).asJson()));

    private final AttachmentCalls calls;

    public ConsentUploadSteps(AttachmentCalls calls) {
        this.calls = calls;
    }

    @Когда("сервис Заявок загружает согласие на обработку ПДн родственника {string} по полису {string}:")
    public void upload(String familyMember, String policy, DataTable table) throws IOException, InterruptedException {
        calls.send(AttachmentUploadSteps.build(path(policy, familyMember), table.asMap(), FILLER));
    }

    @Когда("сервис Заявок загружает согласие на обработку ПДн родственника {string} по полису {string} с дефектом {string}")
    public void uploadWithDefect(String familyMember, String policy, String defect)
            throws IOException, InterruptedException {
        BiFunction<String, String, AttachmentRequest> spoil = Optional.ofNullable(DEFECTS.get(defect))
                .orElseThrow(() -> new AssertionError("дефекта «" + defect + "» нет в перечне шага"));
        calls.send(spoil.apply(policy, familyMember));
    }

    @Когда("сервис Заявок загружает согласие на обработку ПДн родственника {string} по полису {string} с заголовком X-Request-Id {string}:")
    public void uploadWithRequestId(String familyMember, String policy, String requestId, DataTable table)
            throws IOException, InterruptedException {
        calls.send(AttachmentUploadSteps.build(path(policy, familyMember), table.asMap(), FILLER)
                .withRequestId(requestId));
    }

    @Когда("сервис Заявок загружает согласие на обработку ПДн родственника {string} по полису {string} с недопустимым заголовком X-Request-Id {string}:")
    public void uploadWithInvalidRequestId(String familyMember, String policy, String kind, DataTable table)
            throws IOException, InterruptedException {
        calls.send(AttachmentUploadSteps.build(path(policy, familyMember), table.asMap(), FILLER)
                .withRequestId(AttachmentUploadSteps.invalidRequestId(kind)));
    }

    private static AttachmentRequest standard(String policy, String familyMember) {
        return new AttachmentRequest(path(policy, familyMember),
                Optional.of(new AttachmentRequest.AttachmentPart(ATTACHMENT, null)),
                Optional.of(new AttachmentRequest.FilePart(FILE_NAME, PDF, AttachmentUploadSteps.content(CONTENT))),
                Optional.empty(), true);
    }

    private static String path(String policy, String familyMember) {
        return "/internal/v1/policies/" + policy + "/family-members/" + familyMember + "/consents";
    }
}
