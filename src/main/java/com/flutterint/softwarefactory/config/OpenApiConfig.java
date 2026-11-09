package com.flutterint.softwarefactory.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI / Swagger UI metadata for the Software Factory API. */
@Configuration
public class OpenApiConfig {

  @Bean
  public OpenAPI softwareFactoryOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("Software Factory API")
                .description(
                    "Autonomous Engineering Operations system — GitHub integration slice. "
                        + "Target repo: FlutterInt/fips-martech-conversion-router (branch: develop).")
                .version("0.0.1-SNAPSHOT")
                .contact(new Contact().name("FlutterInt").url("https://github.com/FlutterInt")))
        .servers(java.util.List.of(new Server().url("/").description("Local")))
        .components(
            new Components()
                .addSecuritySchemes(
                    "githubToken",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .description("GitHub PAT used server-side; not required on these endpoints.")));
  }
}

