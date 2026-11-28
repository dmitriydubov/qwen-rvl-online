package ru.projectx.contracts.controller;

class MissingFileNameException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    MissingFileNameException() {
        super("имя файла не передано");
    }
}
