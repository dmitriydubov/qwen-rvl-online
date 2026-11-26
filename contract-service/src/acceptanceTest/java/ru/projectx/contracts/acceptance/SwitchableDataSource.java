package ru.projectx.contracts.acceptance;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.Optional;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.datasource.DelegatingDataSource;

final class SwitchableDataSource extends DelegatingDataSource {

    private volatile @Nullable String failingSqlState;

    SwitchableDataSource(DataSource pool) {
        super(pool);
    }

    void failConnectionsWith(String sqlState) {
        failingSqlState = sqlState;
    }

    boolean failing() {
        return failingSqlState != null;
    }

    void restore() {
        failingSqlState = null;
    }

    @Override
    public Connection getConnection() throws SQLException {
        failIfUnavailable();
        return super.getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        failIfUnavailable();
        return super.getConnection(username, password);
    }

    private void failIfUnavailable() throws SQLException {
        Optional<String> sqlState = Optional.ofNullable(failingSqlState);
        if (sqlState.isPresent()) {
            throw new SQLTransientConnectionException("стенд приёмки: база недоступна", sqlState.get());
        }
    }
}
