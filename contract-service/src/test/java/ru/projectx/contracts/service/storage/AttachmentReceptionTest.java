package ru.projectx.contracts.service.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.util.unit.DataSize;
import ru.projectx.contracts.config.storage.AttachmentProperties;
import ru.projectx.contracts.dto.AttachmentResponse;
import ru.projectx.contracts.dto.ConsentResponse;
import ru.projectx.contracts.entity.document.StoredFile;
import ru.projectx.contracts.exception.AttachmentConflictException;
import ru.projectx.contracts.exception.AttachmentRejectedException;
import ru.projectx.contracts.exception.StorageUnavailableException;

class AttachmentReceptionTest {

    private static final String KEY = "certificates/0b6c1d2e-7f3a-4b5c-9d8e-1f2a3b4c5d6e/" + Uploads.ATTACHMENT + ".jpg";
    private static final String PDF_KEY = "certificates/0b6c1d2e-7f3a-4b5c-9d8e-1f2a3b4c5d6e/" + Uploads.ATTACHMENT + ".pdf";
    private static final AttachmentResponse RESPONSE = new AttachmentResponse(UUID.randomUUID(), Uploads.ATTACHMENT,
            Uploads.REQUEST, Uploads.PARTICIPANT, "image/jpeg", 76, Uploads.CONTENT_HASH,
            Instant.parse("2026-02-10T07:05:00Z"));
    private static final ConsentResponse CONSENT_RESPONSE = new ConsentResponse(UUID.randomUUID(), Uploads.ATTACHMENT,
            Uploads.POLICY, Uploads.FAMILY_MEMBER, "application/pdf", 76, Uploads.CONTENT_HASH,
            Instant.parse("2026-02-10T07:05:00Z"));

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final AttachmentMetrics metrics = new AttachmentMetrics(meters);
    private final AttachmentRegistry registry = mock(AttachmentRegistry.class);
    private final ObjectKeys keys = mock(ObjectKeys.class);
    private final CertificateStore store = mock(CertificateStore.class);
    private final AttachmentReception reception = new AttachmentReception(
            new AttachmentFiles(new AttachmentProperties(DataSize.ofMegabytes(5),
                    Map.of("image/jpeg", "jpg", "application/pdf", "pdf")), metrics),
            registry, keys, store, metrics);

    @Test
    void newCertificate_isStoredUnderANewKeyThenRegisteredAndCounted() {
        CertificateUpload upload = Uploads.jpeg();
        when(registry.accepted(any(), eq(Uploads.CONTENT_HASH))).thenReturn(Optional.empty());
        when(keys.next(Uploads.ATTACHMENT, "jpg")).thenReturn(KEY);
        when(registry.register(any(), any(StoredFile.class))).thenReturn(RESPONSE);

        assertThat(reception.receive(upload)).isSameAs(RESPONSE);

        StoredFile expected = new StoredFile(KEY, Uploads.CONTENT_HASH, 76, "image/jpeg");
        InOrder order = inOrder(store, registry);
        order.verify(store).store(eq(expected), any(ContentSource.class));
        order.verify(registry).register(any(), eq(expected));
        assertThat(meters.get("contracts.attachments.stored").counter().count()).isEqualTo(1);
    }

    @Test
    void newConsent_isStoredUnderANewKeyThenRegisteredAndCounted() {
        ConsentUpload upload = Uploads.consentPdf();
        when(registry.accepted(any(), eq(Uploads.CONTENT_HASH))).thenReturn(Optional.empty());
        when(keys.next(Uploads.ATTACHMENT, "pdf")).thenReturn(PDF_KEY);
        when(registry.register(any(), any(StoredFile.class))).thenReturn(CONSENT_RESPONSE);

        assertThat(reception.receive(upload)).isSameAs(CONSENT_RESPONSE);

        StoredFile expected = new StoredFile(PDF_KEY, Uploads.CONTENT_HASH, 76, "application/pdf");
        InOrder order = inOrder(store, registry);
        order.verify(store).store(eq(expected), any(ContentSource.class));
        order.verify(registry).register(any(), eq(expected));
        assertThat(meters.get("contracts.attachments.stored").counter().count()).isEqualTo(1);
    }

    @Test
    void storedObject_receivesTheUploadedContent() throws IOException {
        CertificateUpload upload = Uploads.jpeg();
        when(registry.accepted(any(), eq(Uploads.CONTENT_HASH))).thenReturn(Optional.empty());
        when(keys.next(Uploads.ATTACHMENT, "jpg")).thenReturn(KEY);

        reception.receive(upload);

        ArgumentCaptor<ContentSource> content = ArgumentCaptor.forClass(ContentSource.class);
        verify(store).store(any(StoredFile.class), content.capture());
        assertThat(content.getValue().open().readAllBytes()).isEqualTo(upload.file().content().open().readAllBytes());
    }

    @Test
    void repeatedCertificateOrConsent_returnsPriorRecordEvenWhenStorageIsUnavailable() {
        doThrow(new StorageUnavailableException(new IOException("down"))).when(store).store(any(), any());
        when(registry.accepted(any(), eq(Uploads.CONTENT_HASH))).thenReturn(Optional.of(RESPONSE));

        assertThat(reception.receive(Uploads.jpeg())).isSameAs(RESPONSE);

        when(registry.accepted(any(), eq(Uploads.CONTENT_HASH))).thenReturn(Optional.of(CONSENT_RESPONSE));

        assertThat(reception.receive(Uploads.consentPdf())).isSameAs(CONSENT_RESPONSE);

        verifyNoInteractions(store, keys);
        verify(registry, never()).register(any(), any());
        assertThat(meters.find("contracts.attachments.stored").counter()).isNull();
        assertThat(meters.find("contracts.storage.unavailable").counter()).isNull();
    }

    @Test
    void rejectedFile_touchesNeitherRegistryNorStorage() {
        assertThatThrownBy(() -> reception.receive(Uploads.of(Uploads.PARTICIPANT, "image/png", Uploads.CONTENT)))
                .isInstanceOf(AttachmentRejectedException.class);

        verifyNoInteractions(registry, store, keys);
    }

    @Test
    void conflictingAttachment_isNotStored() {
        when(registry.accepted(any(), eq(Uploads.CONTENT_HASH)))
                .thenThrow(new AttachmentConflictException(Uploads.ATTACHMENT));

        assertThatThrownBy(() -> reception.receive(Uploads.jpeg())).isInstanceOf(AttachmentConflictException.class);

        verifyNoInteractions(store);
    }

    @Test
    void unavailableStorage_isCountedAndRethrownWithoutRegistering() {
        when(registry.accepted(any(), eq(Uploads.CONTENT_HASH))).thenReturn(Optional.empty());
        when(keys.next(Uploads.ATTACHMENT, "jpg")).thenReturn(KEY);
        StorageUnavailableException unavailable = new StorageUnavailableException(new IOException("refused"));
        doThrow(unavailable).when(store).store(any(), any());

        assertThatThrownBy(() -> reception.receive(Uploads.jpeg())).isSameAs(unavailable);

        verify(registry, never()).register(any(), any());
        assertThat(meters.get("contracts.storage.unavailable").counter().count()).isEqualTo(1);
    }

    @Test
    void concurrentUploadOfTheSameAttachment_returnsWinningRecordOrThrowsConflictWithoutCounting() {
        when(registry.accepted(any(), eq(Uploads.CONTENT_HASH))).thenReturn(Optional.empty())
                .thenReturn(Optional.of(CONSENT_RESPONSE));
        when(keys.next(Uploads.ATTACHMENT, "pdf")).thenReturn(PDF_KEY);
        when(registry.register(any(), any())).thenThrow(new DataIntegrityViolationException("uq"));

        assertThat(reception.receive(Uploads.consentPdf())).isSameAs(CONSENT_RESPONSE);
        assertThat(meters.find("contracts.attachments.stored").counter()).isNull();

        when(registry.accepted(any(), eq(Uploads.CONTENT_HASH))).thenReturn(Optional.empty())
                .thenThrow(new AttachmentConflictException(Uploads.ATTACHMENT));

        assertThatThrownBy(() -> reception.receive(Uploads.consentPdf())).isInstanceOf(AttachmentConflictException.class);
        assertThat(meters.find("contracts.attachments.stored").counter()).isNull();
    }

    @Test
    void integrityViolationWithoutAWinningRecord_isRethrown() {
        when(registry.accepted(any(), eq(Uploads.CONTENT_HASH))).thenReturn(Optional.empty());
        when(keys.next(Uploads.ATTACHMENT, "jpg")).thenReturn(KEY);
        DataIntegrityViolationException violation = new DataIntegrityViolationException("fk");
        when(registry.register(any(), any())).thenThrow(violation);

        assertThatThrownBy(() -> reception.receive(Uploads.jpeg())).isSameAs(violation);
    }
}
