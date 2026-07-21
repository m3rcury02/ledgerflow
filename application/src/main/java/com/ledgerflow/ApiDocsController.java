package com.ledgerflow;

import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Deliberately placed under {@code apidocs/} rather than Boot's magic {@code static/}/{@code
 * public/} folders, so these three resources are reachable only through this controller - and
 * therefore only when {@link ApiDocsConfiguration} registered it in the first place.
 */
@RestController
class ApiDocsController {

  private static final MediaType YAML = MediaType.valueOf("application/yaml");

  private final byte[] indexHtml = read("apidocs/index.html");
  private final byte[] swaggerInitializerJs = read("apidocs/swagger-initializer.js");
  private final byte[] openApiYaml = read("ledgerflow.yaml");

  @GetMapping(value = "/docs", produces = MediaType.TEXT_HTML_VALUE)
  ResponseEntity<byte[]> index() {
    return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(indexHtml);
  }

  @GetMapping(value = "/docs/swagger-initializer.js", produces = "application/javascript")
  ResponseEntity<byte[]> swaggerInitializer() {
    return ResponseEntity.ok()
        .contentType(MediaType.valueOf("application/javascript"))
        .body(swaggerInitializerJs);
  }

  @GetMapping(value = "/openapi/ledgerflow.yaml")
  ResponseEntity<byte[]> openApiSpec() {
    return ResponseEntity.ok().contentType(YAML).body(openApiYaml);
  }

  private static byte[] read(String classpathLocation) {
    Resource resource = new ClassPathResource(classpathLocation);
    try {
      return resource.getContentAsByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException("Missing packaged API docs asset: " + classpathLocation, e);
    }
  }
}
