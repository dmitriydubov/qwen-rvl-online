package ru.projectx.contracts.entity.document;

public enum PrintedFormType {
    CONTRACT(true), POLICY(true), APPENDIX(true), MEDICAL_CERTIFICATE(false), PD_CONSENT(false);

    private final boolean issuedByUsk;

    PrintedFormType(boolean issuedByUsk) {
        this.issuedByUsk = issuedByUsk;
    }

    public boolean issuedByUsk() {
        return issuedByUsk;
    }
}
