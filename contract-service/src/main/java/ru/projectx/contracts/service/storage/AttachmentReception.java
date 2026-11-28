package ru.projectx.contracts.service.storage;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import ru.projectx.contracts.dto.AttachmentResponse;
import ru.projectx.contracts.dto.ConsentResponse;
import ru.projectx.contracts.entity.document.StoredFile;
import ru.projectx.contracts.exception.StorageUnavailableException;

@Service
public class AttachmentReception implements AttachmentUploads, ConsentUploads {

    private final AttachmentFiles files;
    private final AttachmentRegistry registry;
    private final ObjectKeys keys;
    private final CertificateStore store;
    private final AttachmentMetrics metrics;

    public AttachmentReception(AttachmentFiles files, AttachmentRegistry registry, ObjectKeys keys,
            CertificateStore store, AttachmentMetrics metrics) {
        this.files = files;
        this.registry = registry;
        this.keys = keys;
        this.store = store;
        this.metrics = metrics;
    }

    @Override
    public AttachmentResponse receive(CertificateUpload upload) {
        return accept(AttachmentTarget.certificate(upload));
    }

    @Override
    public ConsentResponse receive(ConsentUpload upload) {
        return accept(AttachmentTarget.consent(upload));
    }

    private <R> R accept(AttachmentTarget<R> target) {
        UploadFile file = target.file();
        AttachmentFiles.AcceptedFile accepted = files.accept(file);
        String fileHash = ContentDigests.sha256(file.content());
        return registry.accepted(target, fileHash).orElseGet(() -> stored(target, new StoredFile(
                keys.next(target.attachmentId(), accepted.extension()), fileHash, file.size(),
                accepted.contentType())));
    }

    private <R> R stored(AttachmentTarget<R> target, StoredFile file) {
        try {
            store.store(file, target.file().content());
        } catch (StorageUnavailableException unavailable) {
            metrics.countStorageUnavailable();
            throw unavailable;
        }
        return registered(target, file);
    }

    private <R> R registered(AttachmentTarget<R> target, StoredFile file) {
        try {
            R response = registry.register(target, file);
            metrics.countStored();
            return response;
        } catch (DataIntegrityViolationException concurrentUpload) {
            return registry.accepted(target, file.hash()).orElseThrow(() -> concurrentUpload);
        }
    }
}
