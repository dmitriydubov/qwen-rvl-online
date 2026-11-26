package ru.projectx.contracts.acceptance;

import org.springframework.stereotype.Component;

@Component
public class StandFaults {

    private boolean databaseOrLeadershipDisturbed;
    private boolean storageDisturbed;

    void markDatabaseOrLeadershipDisturbed() {
        databaseOrLeadershipDisturbed = true;
    }

    boolean databaseOrLeadershipDisturbed() {
        return databaseOrLeadershipDisturbed;
    }

    void clearDatabaseOrLeadershipDisturbed() {
        databaseOrLeadershipDisturbed = false;
    }

    void markStorageDisturbed() {
        storageDisturbed = true;
    }

    boolean storageDisturbed() {
        return storageDisturbed;
    }

    void clearStorageDisturbed() {
        storageDisturbed = false;
    }
}
