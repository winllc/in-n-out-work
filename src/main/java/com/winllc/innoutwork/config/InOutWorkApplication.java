package com.winllc.innoutwork.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

@SpringBootApplication(scanBasePackages = "com.winllc.innoutwork")
@EntityScan(basePackages = "com.winllc.innoutwork.model")
@EnableJpaRepositories(basePackages = "com.winllc.innoutwork.repository")
@EnableAsync
@EnableCaching
@EnableScheduling
@ConfigurationPropertiesScan
public class InOutWorkApplication {

    @Value("${application.timezone:UTC}")
    private String applicationTimeZone;

    public static void main(String[] args) {
        SpringApplication.run(InOutWorkApplication.class, args);
    }

    @PostConstruct
    public void postConstruct(){
        TimeZone.setDefault(TimeZone.getTimeZone(applicationTimeZone));
    }
}