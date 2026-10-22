package com.winllc.innoutwork.api;

import com.winllc.innoutwork.support.FullApplicationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The OpenAPI 3 document of /api/v1: served to anyone, generated from the controllers, its server the configured
 * public base URL whatever Host the request names. The build writes it to {@code build/openapi/readyroom-api-v1.json}
 * (CI keeps it as an artifact) and checks that {@code docs/api/readyroom-api-v1.json} is up to date; regenerate
 * that with {@code ./gradlew test --tests '*OpenApiDocumentTest' -Dopenapi.update=true}.
 */
class OpenApiDocumentTest extends FullApplicationTest {

    static final Path COMMITTED = Path.of("docs/api/readyroom-api-v1.json");
    static final Path GENERATED = Path.of("build/openapi/readyroom-api-v1.json");

    @Autowired MockMvc mockMvc;

    @Test
    void theDocumentDescribesTheApiAndIsCommittedUpToDate() throws Exception {
        String json = mockMvc.perform(get("/api/v1/openapi").header("Host", "evil.example"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode doc = new ObjectMapper().readTree(json);

        assertTrue(doc.get("openapi").asString().startsWith("3."), "OpenAPI 3");
        assertEquals("https://readyroom.example.com", doc.get("servers").get(0).get("url").asString());
        assertTrue(!json.contains("evil.example"), "never the request's host");
        for (String path : List.of("/api/v1/people", "/api/v1/attendance/events", "/api/v1/absences",
                "/api/v1/absences/requests")) {
            assertTrue(doc.get("paths").has(path), path);
            JsonNode get = doc.get("paths").get(path).get("get");
            for (String code : List.of("200", "400", "401", "403", "429")) {
                assertTrue(get.get("responses").has(code), path + " " + code);
            }
        }
        assertTrue(doc.get("paths").properties().stream().allMatch(e -> e.getKey().startsWith("/api/v1/")),
                "only /api/v1 is described");
        assertEquals("bearer", doc.get("components").get("securitySchemes").get("apiToken").get("scheme").asString());

        String yaml = mockMvc.perform(get("/api/v1/openapi.yaml")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(yaml.contains("openapi: 3."), yaml.substring(0, Math.min(200, yaml.length())));

        String normalized = json.strip().replace("\r\n", "\n") + "\n";
        Files.createDirectories(GENERATED.getParent());
        Files.writeString(GENERATED, normalized);
        if (Boolean.getBoolean("openapi.update")) {
            Files.createDirectories(COMMITTED.getParent());
            Files.writeString(COMMITTED, normalized);
        }
        assertTrue(Files.exists(COMMITTED), COMMITTED + " is missing: run with -Dopenapi.update=true");
        assertEquals(Files.readString(COMMITTED), normalized, COMMITTED + " is out of date with the API: regenerate it"
                + " with ./gradlew test --tests '*OpenApiDocumentTest' -Dopenapi.update=true");
    }
}
