package ru.projectx.contracts.acceptance;

import io.cucumber.java.Before;
import io.cucumber.java.ru.Дано;
import java.util.List;

public class StandCleanupHook {

    private final StandTables tables;
    private final List<StandQueue> queues;
    private final StatusQueue statusQueue;
    private final ReferenceStub reference;

    public StandCleanupHook(StandTables tables, List<StandQueue> queues, StatusQueue statusQueue,
            ReferenceStub reference) {
        this.tables = tables;
        this.queues = List.copyOf(queues);
        this.statusQueue = statusQueue;
        this.reference = reference;
    }

    @Before(order = 10)
    public void emptyStand() {
        statusQueue.declare();
        queues.forEach(StandQueue::purge);
        reference.reset();
        tables.truncateNonEmpty();
    }

    @Дано("нумерация договоров КМ начата заново")
    public void numberingRestarted() {
        tables.resetNumbering();
    }
}
