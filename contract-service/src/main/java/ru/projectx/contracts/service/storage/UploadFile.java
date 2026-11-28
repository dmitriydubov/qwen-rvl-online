package ru.projectx.contracts.service.storage;

import org.jspecify.annotations.Nullable;

public record UploadFile(
        @Nullable String contentType,
        long size,
        ContentSource content) {
}
