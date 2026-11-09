package com.flutterint.softwarefactory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SoftwareFactoryApplication {

  public static void main(String[] args) {
    SpringApplication.run(SoftwareFactoryApplication.class, args);
  }
}

