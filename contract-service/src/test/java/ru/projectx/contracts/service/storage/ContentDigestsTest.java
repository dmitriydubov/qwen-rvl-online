package ru.projectx.contracts.service.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ContentDigestsTest {

    @Test
    void sha256_isLowercaseHexOfTheWholeContent() {
        byte[] content = "abc".getBytes(StandardCharsets.US_ASCII);

        assertThat(ContentDigests.sha256(() -> new ByteArrayInputStream(content)))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void sha256_ofTheStandCertificate_matchesTheSpecification() {
        assertThat(ContentDigests.sha256(Uploads.jpeg().file().content())).isEqualTo(Uploads.CONTENT_HASH);
    }

    @Test
    void unreadableContent_isUncheckedFailureWithoutTheContent() {
        assertThatThrownBy(() -> ContentDigests.sha256(() -> {
            throw new IOException("поток закрыт");
        })).isInstanceOf(UncheckedIOException.class).hasMessageStartingWith("файл справки не прочитан");
    }
}
