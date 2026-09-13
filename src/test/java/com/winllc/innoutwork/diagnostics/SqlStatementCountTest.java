package com.winllc.innoutwork.diagnostics;

import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.UserRecordRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** With request metrics on, Hibernate reports each statement it runs. */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sqlcount;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "application.diagnostics.request-metrics=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(RequestMetricsConfig.class)
class SqlStatementCountTest {

    @Configuration
    @AutoConfigurationPackage(basePackages = "com.winllc.innoutwork")
    static class JpaConfig {
    }

    @Autowired
    private UserRecordRepository users;

    @Test
    void eachQueryIsCounted() {
        users.saveAndFlush(UserRecord.builder().dn("cn=Bob,ou=Users,dc=winllc,dc=com").build());

        RequestMetrics.start();
        users.findByDnIgnoreCase("CN=BOB,OU=USERS,DC=WINLLC,DC=COM");
        users.findAllByLowercaseDnIn(List.of("cn=bob,ou=users,dc=winllc,dc=com"));
        RequestMetrics.Counts counts = RequestMetrics.stop();

        assertEquals(2, counts.sqlStatements());
    }
}
