package ru.projectx.contracts.acceptance;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ru.projectx.contracts.service.leadership.LeaderLock;

@Component
public class LeaderLockStand {

    static final String OTHER_INSTANCE = "7e3a1b2c-4d5e-4f60-8a71-92b3c4d5e6f7";

    private static final Duration HELD_FOR = Duration.ofHours(1);
    private static final Duration EXPIRED_FOR = Duration.ofMinutes(1);

    private static final String TAKE_OVER = """
            insert into leader_lock (region, lock_key, client_id, created_date, expired_after)
            values (:group, :key, :other, :now, :expires)
            on conflict (lock_key, region) do update set client_id = :other, expired_after = :expires
            """;
    private static final String RELEASE = """
            delete from leader_lock where region = :group and lock_key = :key and client_id = :other
            """;
    private static final String EXPIRE = """
            update leader_lock set expired_after = :expires
             where region = :group and lock_key = :key and client_id = :other
            """;

    private final JdbcClient jdbc;
    private final LeaderLock lock;
    private boolean heldByOther;

    public LeaderLockStand(StandDatabase database, LeaderLock lock) {
        this.jdbc = database.jdbc();
        this.lock = lock;
    }

    boolean heldByOther() {
        return heldByOther;
    }

    void takeOver() {
        LocalDateTime now = now();
        jdbc.sql(TAKE_OVER)
                .param("group", lock.group())
                .param("key", lock.key())
                .param("other", OTHER_INSTANCE)
                .param("now", now)
                .param("expires", now.plus(HELD_FOR))
                .update();
        heldByOther = true;
    }

    void expire() {
        jdbc.sql(EXPIRE)
                .param("group", lock.group())
                .param("key", lock.key())
                .param("other", OTHER_INSTANCE)
                .param("expires", now().minus(EXPIRED_FOR))
                .update();
    }

    void release() {
        jdbc.sql(RELEASE)
                .param("group", lock.group())
                .param("key", lock.key())
                .param("other", OTHER_INSTANCE)
                .update();
        heldByOther = false;
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
