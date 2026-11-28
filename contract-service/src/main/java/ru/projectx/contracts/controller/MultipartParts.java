package ru.projectx.contracts.controller;

import org.springframework.web.multipart.MultipartFile;
import ru.projectx.contracts.service.storage.UploadFile;

final class MultipartParts {

    private MultipartParts() {
    }

    static UploadFile requireFile(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank()) {
            throw new MissingFileNameException();
        }
        return new UploadFile(file.getContentType(), file.getSize(), file::getInputStream);
    }
}
