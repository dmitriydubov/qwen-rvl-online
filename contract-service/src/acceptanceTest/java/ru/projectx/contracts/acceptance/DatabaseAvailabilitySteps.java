package ru.projectx.contracts.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.cucumber.java.After;
import io.cucumber.java.ru.Дано;
import io.cucumber.java.ru.Когда;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;
import ru.projectx.contracts.service.integration.consumer.InboxConsumerPause;
import ru.projectx.contracts.service.leadership.BackgroundLeadership;
import ru.projectx.contracts.service.leadership.LeaderLockConnections;

public class DatabaseAvailabilitySteps {

    private static final int AFTER_LOCK_RELEASE = 100;
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(45);

    private final List<SwitchableDataSource> pools;
    private final InboxConsumerPause pause;
    private final BackgroundLeadership leadership;
    private final ConsumedQueues consumers;
    private final StandFaults faults;

    public DatabaseAvailabilitySteps(DataSource dataSource, LeaderLockConnections leaderLock, InboxConsumerPause pause,
            BackgroundLeadership leadership, ConsumedQueues consumers, StandFaults faults) {
        assertThat(dataSource).as("пул соединений стенда с выключателем").isInstanceOf(SwitchableDataSource.class);
        assertThat(leaderLock).as("пул блокировки ведущего с выключателем")
                .isInstanceOf(SwitchableLeaderLockConnections.class);
        this.pools = List.of((SwitchableDataSource) dataSource, ((SwitchableLeaderLockConnections) leaderLock).connections());
        this.pause = pause;
        this.leadership = leadership;
        this.consumers = consumers;
        this.faults = faults;
    }

    @Дано("каждое соединение с базой Договоров отказывает с SQLSTATE {string}")
    public void connectionsFail(String sqlState) {
        faults.markDatabaseOrLeadershipDisturbed();
        pools.forEach(pool -> pool.failConnectionsWith(sqlState));
    }

    @Когда("база Договоров снова принимает соединения")
    public void connectionsRestored() {
        pools.forEach(SwitchableDataSource::restore);
    }

    @Когда("Договоры проверяют доступность базы")
    public void storageChecked() {
        await().atMost(STOP_TIMEOUT).until(pause::consumersStopped);
        pause.resumeIfStorageAvailable();
        if (pause.paused().isEmpty() && leadership.leader()) {
            consumers.awaitSubscribedOrStopped();
        }
    }

    @After(order = AFTER_LOCK_RELEASE)
    public void restoreAndResume() {
        if (faults.databaseOrLeadershipDisturbed() || !pause.paused().isEmpty()) {
            connectionsRestored();
            storageChecked();
        }
    }
}
