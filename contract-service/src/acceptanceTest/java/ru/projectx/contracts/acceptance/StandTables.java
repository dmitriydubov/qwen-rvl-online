package ru.projectx.contracts.acceptance;

import java.util.List;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class StandTables {

    private static final String DOMAIN_TABLES = """
            select quote_ident(tablename)
              from pg_tables
             where schemaname = 'public'
               and tablename not like 'databasechangelog%'
               and tablename <> 'leader_lock'
             order by tablename
            """;

    private final JdbcClient jdbc;
    private final String cleanupSql;

    public StandTables(JdbcClient jdbc) {
        this.jdbc = jdbc;
        this.cleanupSql = truncateBlock(jdbc.sql(DOMAIN_TABLES).query(String.class).list());
    }

    void truncateNonEmpty() {
        jdbc.sql(cleanupSql).update();
    }

    void resetNumbering() {
        jdbc.sql("delete from contract_number_counters").update();
    }

    private static String truncateBlock(List<String> tables) {
        String checks = tables.stream()
                .map(table -> "if exists (select 1 from " + table + " limit 1) then "
                        + "targets := array_append(targets, '" + table.replace("'", "''") + "'); end if;")
                .collect(Collectors.joining("\n    "));
        return "do $$\ndeclare\n    targets text[] := '{}';\nbegin\n    " + checks
                + "\n    if cardinality(targets) > 0 then\n"
                + "        execute 'truncate table ' || array_to_string(targets, ', ') || ' cascade';\n"
                + "    end if;\nend $$;";
    }
}
