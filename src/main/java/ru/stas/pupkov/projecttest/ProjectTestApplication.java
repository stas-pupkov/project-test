package ru.stas.pupkov.projecttest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class ProjectTestApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProjectTestApplication.class, args);
    }

}
