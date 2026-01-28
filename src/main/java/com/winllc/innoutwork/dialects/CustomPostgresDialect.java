package com.winllc.innoutwork.dialects;

import org.hibernate.dialect.PostgreSQLDialect;

public class CustomPostgresDialect extends PostgreSQLDialect {

    @Override
    public String getCheckCondition(String columnName, String[] values) {
        // We do not want enum database checks
        return null;
    }

}
