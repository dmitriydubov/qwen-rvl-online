package ru.projectx.contracts.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.java.After;
import io.cucumber.java.ru.Дано;
import io.cucumber.java.ru.Когда;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;

public class DatabaseLockSteps {

    private static final Map<String, String> TABLES = Map.of(
            "журнал входящих сообщений", "inbox_messages",
            "журнал исходящих сообщений", "outbox_messages",
            "договоры КМ", "contracts",
            "полисы ЛМ", "lm_policies");
    private static final Map<String, String> RECORD_TABLES = Map.of(
            "договор КМ", "contracts",
            "полис ЛМ", "lm_policies");

    private final IssuanceRecords records;
    private final DataSource dataSource;
    private final StandFaults faults;
    private @Nullable Connection lockingSession;

    public DatabaseLockSteps(DataSource dataSource, IssuanceRecords records, StandFaults faults) {
        this.dataSource = dataSource;
        this.records = records;
        this.faults = faults;
    }

    @Дано("данные {string} в базе Договоров заблокированы долгой операцией")
    public void dataLocked(String data) throws SQLException {
        assertThat(TABLES).as("данные, которые умеет блокировать шаг").containsKey(data);
        faults.markDatabaseOrLeadershipDisturbed();
        Connection session = dataSource.getConnection();
        lockingSession = session;
        session.setAutoCommit(false);
        try (Statement statement = session.createStatement()) {
            statement.execute("lock table " + TABLES.get(data) + " in exclusive mode");
        }
    }

    @Дано("запись о выпуске участника {string} заявки {string} в базе Договоров заблокирована долгой операцией")
    public void recordLocked(String participantId, String requestId) throws SQLException {
        IssuanceRecords.Record record = records.of(participantId, requestId).getLast();
        faults.markDatabaseOrLeadershipDisturbed();
        Connection session = dataSource.getConnection();
        lockingSession = session;
        session.setAutoCommit(false);
        String table = RECORD_TABLES.get(record.document());
        try (PreparedStatement statement = session.prepareStatement("select id from " + table + " where id = ? for update")) {
            statement.setObject(1, record.id());
            assertThat(statement.executeQuery().next()).as("строка записи %s заблокирована", record.id()).isTrue();
        }
    }

    @Когда("долгая операция в базе Договоров завершилась")
    public void operationFinished() throws SQLException {
        assertThat(lockingSession).as("блокировку взял шаг «заблокированы долгой операцией»").isNotNull();
        release();
    }

    @After
    public void release() throws SQLException {
        Optional<Connection> session = Optional.ofNullable(lockingSession);
        lockingSession = null;
        if (session.isPresent()) {
            try (Connection connection = session.get()) {
                connection.rollback();
            }
        }
    }
}
