package ru.projectx.contracts.service.storage;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import ru.projectx.contracts.exception.AttachmentDefect;

class AttachmentMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AttachmentMetrics metrics = new AttachmentMetrics(registry);

    @Test
    void countStored_incrementsCounterWithoutTags() {
        metrics.countStored();
        metrics.countStored();

        assertThat(registry.get("contracts.attachments.stored").counter().count()).isEqualTo(2);
        assertThat(registry.get("contracts.attachments.stored").counter().getId().getTags()).isEmpty();
        assertThat(registry.get("contracts.attachments.stored").counter().getId().getDescription())
                .isEqualTo("вложения, впервые записанные в реестр документов");
    }

    @Test
    void countRejected_tagsTheLowercaseDefectOnly() {
        metrics.countRejected(AttachmentDefect.CONTENT_TYPE);
        metrics.countRejected(AttachmentDefect.SIZE);
        metrics.countRejected(AttachmentDefect.SIZE);

        assertThat(registry.get("contracts.attachments.rejected").tag("reason", "content_type").counter().count())
                .isEqualTo(1);
        assertThat(registry.get("contracts.attachments.rejected").tag("reason", "size").counter().count()).isEqualTo(2);
        assertThat(registry.get("contracts.attachments.rejected").tag("reason", "size").counter().getId().getTags())
                .hasSize(1);
        assertThat(registry.get("contracts.attachments.rejected").tag("reason", "size").counter().getId()
                .getDescription()).isEqualTo("файлы вложений, отклонённые по типу или размеру");
    }

    @Test
    void countStorageUnavailable_incrementsCounterWithoutTags() {
        metrics.countStorageUnavailable();

        assertThat(registry.get("contracts.storage.unavailable").counter().count()).isEqualTo(1);
        assertThat(registry.get("contracts.storage.unavailable").counter().getId().getTags()).isEmpty();
        assertThat(registry.get("contracts.storage.unavailable").counter().getId().getDescription())
                .isEqualTo("обращения к хранилищу S3, не получившие успешного ответа");
    }
}
