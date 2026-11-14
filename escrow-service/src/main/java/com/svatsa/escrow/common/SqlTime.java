package com.svatsa.escrow.common;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * pgjdbc cannot bind {@link Instant} ("Can't infer the SQL type"), but it binds
 * {@link OffsetDateTime} to TIMESTAMPTZ natively. Wrap every Instant passed to JdbcClient.
 */
public final class SqlTime {
    private SqlTime() {}

    public static OffsetDateTime ts(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    /**
     * Read a TIMESTAMPTZ column as an Instant. pgjdbc does not support getObject(col, Instant.class)
     * ("conversion to class java.time.Instant from timestamp not supported").
     */
    public static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
