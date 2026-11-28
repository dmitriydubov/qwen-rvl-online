package ru.projectx.contracts.service.storage;

import ru.projectx.contracts.dto.ConsentResponse;

public interface ConsentUploads {

    ConsentResponse receive(ConsentUpload upload);
}
