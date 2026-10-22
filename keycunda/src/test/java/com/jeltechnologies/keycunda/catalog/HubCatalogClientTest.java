package com.jeltechnologies.keycunda.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.jeltechnologies.keycunda.catalog.HubCatalogClient.CatalogAsset;
import com.jeltechnologies.keycunda.catalog.HubCatalogClient.CatalogTemplate;
import com.sun.net.httpserver.HttpServer;

class HubCatalogClientTest {

    private record Seen(String method, String path, String authorization, String contentType, String body) {
    }

    private HttpServer server;
    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String responseBody = "{}";
    private HubCatalogClient client;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hub/api/v2", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.add(new Seen(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Content-Type"), body));
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", status >= 400 ? "application/problem+json" : "application/json");
            exchange.sendResponseHeaders(status, bytes.length == 0 || status == 204 ? -1 : bytes.length);
            if (bytes.length > 0 && status != 204) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
        client = new HubCatalogClient("http://127.0.0.1:" + server.getAddress().getPort() + "/hub/", () -> "tok");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void listsAssetsWithBearerToken() throws IOException {
        responseBody = """
                {"items":[{"assetKey":"k1","resourceId":"io.a","name":"A","latestVersion":2,"unpublished":false},
                          {"assetKey":"k2","resourceId":"io.b","name":"B","latestVersion":1,"unpublished":true}],
                 "page":{"totalItems":2}}
                """;

        List<CatalogAsset> assets = client.listAssets();

        assertThat(assets).extracting(CatalogAsset::resourceId).containsExactly("io.a", "io.b");
        assertThat(assets.get(1).unpublished()).isTrue();
        assertThat(seen).singleElement().satisfies(s -> {
            assertThat(s.method()).isEqualTo("POST");
            assertThat(s.path()).isEqualTo("/hub/api/v2/catalog/assets/search");
            assertThat(s.authorization()).isEqualTo("Bearer tok");
            assertThat(s.body()).contains("\"from\":0").contains("\"limit\":100");
        });
    }

    @Test
    void pagesThroughMoreThanOnePageOfAssets() throws IOException {
        List<String> items = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            items.add("{\"assetKey\":\"k" + i + "\",\"resourceId\":\"id" + i + "\",\"name\":\"n\",\"latestVersion\":1}");
        }
        responseBody = "{\"items\":[" + String.join(",", items) + "],\"page\":{\"totalItems\":100}}";

        assertThat(client.listAssets()).hasSize(100);
        assertThat(seen).hasSize(1);
    }

    @Test
    void ingestsTemplatesAsMultipartWithReadmeParts() throws IOException {
        status = 204;
        responseBody = "";

        client.ingest(List.of(new CatalogTemplate("io.a.json", "{\"id\":\"io.a\"}", "---\nreadme\n")));

        assertThat(seen).singleElement().satisfies(s -> {
            assertThat(s.method()).isEqualTo("PUT");
            assertThat(s.path()).isEqualTo("/hub/api/v2/catalog/assets/ingestion");
            assertThat(s.contentType()).startsWith("multipart/form-data");
            assertThat(s.body()).contains("name=\"template\"").contains("filename=\"io.a.json\"")
                    .contains("name=\"readme\"").contains("filename=\"io.a.md\"").contains("{\"id\":\"io.a\"}");
        });
    }

    @Test
    void refusesAnEmptySubmissionWithoutCallingHub() {
        assertThatThrownBy(() -> client.ingest(List.of())).isInstanceOf(IOException.class).hasMessageContaining("empty");
        assertThat(seen).isEmpty();
    }

    @Test
    void deletesAnAsset() throws IOException {
        status = 204;
        responseBody = "";

        client.deleteAsset(new CatalogAsset("k1", "io.a", "A", 1, false));

        assertThat(seen).singleElement().satisfies(s -> {
            assertThat(s.method()).isEqualTo("DELETE");
            assertThat(s.path()).isEqualTo("/hub/api/v2/catalog/assets/k1");
        });
    }

    @Test
    void reportsProblemDetailsAndAccessHints() {
        status = 403;
        responseBody = "{\"title\":\"Forbidden\",\"detail\":\"no permission\"}";

        assertThatThrownBy(() -> client.listAssets()).isInstanceOf(HubCatalogClient.HubApiException.class)
                .hasMessageContaining("HTTP 403: Forbidden: no permission").hasMessageContaining("web-modeler-public-api");
    }

    @Test
    void aWebPageInsteadOfJsonMeansTheBaseUrlIsWrong() {
        responseBody = "<html>login</html>";

        assertThatThrownBy(() -> client.listAssets()).hasMessageContaining("web page").hasMessageContaining("base-url");
    }
}
