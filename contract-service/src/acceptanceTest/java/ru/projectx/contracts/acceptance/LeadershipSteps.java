package ru.projectx.contracts.acceptance;

import static org.awaitility.Awaitility.await;

import io.cucumber.java.After;
import io.cucumber.java.ru.Дано;
import io.cucumber.java.ru.Когда;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.projectx.contracts.entity.inbox.InboxMessage;
import ru.projectx.contracts.entity.inbox.InboxReceipt;
import ru.projectx.contracts.repository.journal.InboxMessageRepository;
import ru.projectx.contracts.service.leadership.BackgroundLeadership;

public class LeadershipSteps {

    private static final int AFTER_DATABASE_RESTORED = 10;
    private static final Duration ROLE_TIMEOUT = Duration.ofSeconds(45);
    private static final String TASK_TOPIC = "rvl.contract-task.upserted";
    private static final String TASK_EVENT = "ContractTaskUpserted";

    private final LeaderLockStand stand;
    private final TaskMessages messages;
    private final TaskQueue queue;
    private final PublishedMessages published;
    private final InboxMessageRepository inbox;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final BackgroundLeadership leadership;
    private final ConsumedQueues consumers;
    private final StandFaults faults;

    public LeadershipSteps(LeaderLockStand stand, TaskMessages messages, TaskQueue queue, PublishedMessages published,
            InboxMessageRepository inbox, PlatformTransactionManager transactions, Clock clock,
            BackgroundLeadership leadership, ConsumedQueues consumers, StandFaults faults) {
        this.stand = stand;
        this.messages = messages;
        this.queue = queue;
        this.published = published;
        this.inbox = inbox;
        this.transaction = new TransactionTemplate(transactions);
        this.clock = clock;
        this.leadership = leadership;
        this.consumers = consumers;
        this.faults = faults;
    }

    @Дано("другой экземпляр Договоров перехватил роль ведущего")
    public void otherInstanceTookOver() {
        faults.markDatabaseOrLeadershipDisturbed();
        stand.takeOver();
    }

    @Когда("другой экземпляр Договоров остановился")
    public void otherInstanceStopped() {
        faults.markDatabaseOrLeadershipDisturbed();
        stand.release();
    }

    @Когда("срок блокировки погибшего экземпляра истёк")
    public void deadInstanceLockExpired() {
        faults.markDatabaseOrLeadershipDisturbed();
        stand.expire();
    }

    @Когда("другой экземпляр Договоров записал в журнал входящих задачу на выпуск {string} по заявке {string} версии {int} и погиб, не подтвердив её брокеру:")
    public void otherInstanceStoredAndDied(String messageId, String requestId, int version,
            List<Map<String, String>> participants) {
        faults.markDatabaseOrLeadershipDisturbed();
        byte[] body = messages.bytes(messages.upserted(messageId, requestId, version, participants));
        InboxReceipt receipt = new InboxReceipt(UUID.fromString(messageId), UUID.fromString(requestId), TASK_TOPIC,
                TASK_EVENT, version);
        transaction.executeWithoutResult(status ->
                inbox.save(InboxMessage.receive(receipt, new String(body, StandardCharsets.UTF_8), clock.instant())));
        published.remember(messageId, body);
        published.rememberReturned(queue.alias());
        queue.publish(body);
    }

    @After(order = AFTER_DATABASE_RESTORED)
    public void thisInstanceLeadsAgain() {
        if (faults.databaseOrLeadershipDisturbed() || stand.heldByOther() || !leadership.leader()) {
            stand.release();
            await().atMost(ROLE_TIMEOUT).until(leadership::leader);
            consumers.awaitEveryConsumer();
            faults.clearDatabaseOrLeadershipDisturbed();
        }
    }
}
