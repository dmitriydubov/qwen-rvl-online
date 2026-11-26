package ru.projectx.contracts.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.cucumber.java.After;
import io.cucumber.java.ru.Дано;
import io.cucumber.java.ru.Когда;
import io.cucumber.java.ru.Тогда;
import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import ru.projectx.contracts.config.storage.AttachmentProperties;
import ru.projectx.contracts.config.storage.StorageProperties;

public class CertificateStorageSteps {

    private static final String SEPARATOR = "/";
    private static final String ORGANIZATION_SEPARATOR = "_";
    private static final Duration SERVICE_RECONNECT = Duration.ofSeconds(30);

    private final StandStorage storage;
    private final StorageProperties settings;
    private final AttachmentProperties attachments;
    private final CertificateRecords records;
    private final AttachmentCalls calls;
    private final MinioClient serviceClient;
    private final StandFaults faults;

    private final Map<String, Runnable> outages;

    public CertificateStorageSteps(StandStorage storage, StorageProperties settings, AttachmentProperties attachments,
            CertificateRecords records, AttachmentCalls calls, MinioClient serviceClient, StandFaults faults) {
        this.storage = storage;
        this.settings = settings;
        this.attachments = attachments;
        this.records = records;
        this.calls = calls;
        this.serviceClient = serviceClient;
        this.faults = faults;
        this.outages = Map.of("остановлено", storage::stop, "не отвечает", storage::pause);
    }

    @After
    public void recoverStorage() {
        if (faults.storageDisturbed() || storage.disrupted()) {
            restoreStorageAndClient();
            faults.clearStorageDisturbed();
        }
    }

    @Дано("хранилище справок Договоров — бакет {string}, base-path {string}, предельный размер файла {long} байт")
    public void storageSettings(String bucket, String basePath, long maxFileSize) throws Exception {
        assertThat(settings.bucket()).as("бакет в настройках стенда").isEqualTo(bucket);
        assertThat(settings.basePath()).as("base-path в настройках стенда").isEqualTo(basePath);
        assertThat(attachments.maxFileSize().toBytes()).as("предельный размер файла").isEqualTo(maxFileSize);
        storage.recreateBucket(bucket);
    }

    @Дано("организация в ключах хранилища справок Договоров — ИНН {string}, КПП {string}")
    public void organization(String inn, String kpp) {
        assertThat(settings.inn()).as("ИНН в настройках стенда").isEqualTo(inn);
        assertThat(settings.kpp()).as("КПП в настройках стенда").isEqualTo(kpp);
    }

    @Дано("хранилище справок недоступно: {string}")
    public void storageDown(String outage) {
        faults.markStorageDisturbed();
        Optional.ofNullable(outages.get(outage))
                .orElseThrow(() -> new AssertionError("недоступности «" + outage + "» нет в перечне шага"))
                .run();
    }

    @Когда("хранилище справок снова доступно")
    public void storageBack() throws Exception {
        restoreStorageAndClient();
        assertThat(storage.bucketExists(settings.bucket())).as("бакет после восстановления").isTrue();
    }

    private void restoreStorageAndClient() {
        storage.recover();
        await("клиент хранилища сервиса снова отвечает").atMost(SERVICE_RECONNECT).ignoreExceptions()
                .until(this::serviceClientAnswers);
    }

    @Дано("в хранилище справок нет бакета Договоров")
    public void bucketRemoved() throws Exception {
        storage.removeBucket(settings.bucket());
    }

    @Тогда("в хранилище справок бакета Договоров по-прежнему нет")
    public void bucketStillMissing() throws Exception {
        assertThat(storage.bucketExists(settings.bucket())).as("бакет %s", settings.bucket()).isFalse();
    }

    @Тогда("в хранилище справок за сценарий появилось объектов — {int}")
    public void objectCount(int count) throws Exception {
        assertThat(storage.keys(settings.bucket(), basePath() + SEPARATOR)).as("объекты бакета").hasSize(count);
    }

    @Тогда("содержимое объекта справки по вложению {string} в хранилище совпадает с отправленным")
    public void objectContent(String attachment) throws Exception {
        String key = records.byAttachment(attachment).key();
        assertThat(storage.object(settings.bucket(), key)).as("объект %s", key)
                .isEqualTo(calls.acceptedContent(attachment));
    }

    @Тогда("папки объектов справок по вложениям {string} и {string} разные")
    public void separateFolders(String first, String second) {
        assertThat(folder(records.byAttachment(first).key())).isNotEqualTo(folder(records.byAttachment(second).key()));
    }

    private String folder(String key) {
        assertThat(key).as("ключ объекта").startsWith(basePath() + SEPARATOR);
        String[] segments = key.substring(basePath().length() + 1).split(SEPARATOR);
        assertThat(segments).as("сегменты ключа %s", key).hasSize(2);
        return segments[0];
    }

    private boolean serviceClientAnswers() throws Exception {
        serviceClient.bucketExists(BucketExistsArgs.builder().bucket(settings.bucket()).build());
        return true;
    }

    private String basePath() {
        return settings.inn() + ORGANIZATION_SEPARATOR + settings.kpp() + SEPARATOR + settings.basePath();
    }
}
