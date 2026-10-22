package com.jeltechnologies.keycunda.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;

class ConnectorTemplateSourceTest {

    private final ConnectorTemplateSource source = new ConnectorTemplateSource("unused", "8.10", Clock.systemUTC());

    private static String template(String id, String name, int version, String description, String engines) {
        return """
                {"id":"%s","name":"%s","version":%d,"description":"%s","appliesTo":["bpmn:Task"],"properties":[],
                 "keywords":["a","b"]%s}
                """.formatted(id, name, version, description,
                engines == null ? "" : ",\"engines\":{\"camunda\":\"" + engines + "\"}");
    }

    private static byte[] zip(Map<String, String> files) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> file : files.entrySet()) {
                zos.putNextEntry(new ZipEntry(file.getKey()));
                zos.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return out.toByteArray();
    }

    @Test
    void keepsOnlyTheHighestVersionOfEachTemplateId() throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("rest-1.json", template("io.camunda.rest", "REST", 1, "Call REST", null));
        files.put("sub/rest-3.json", template("io.camunda.rest", "REST", 3, "Call REST", null));
        files.put("rest-2.json", template("io.camunda.rest", "REST", 2, "Call REST", null));

        List<ConnectorTemplate> result = source.parse(zip(files));

        assertThat(result).singleElement().satisfies(t -> {
            assertThat(t.version()).isEqualTo(3);
            assertThat(t.keywords()).containsExactly("a", "b");
        });
    }

    @Test
    void dropsDeprecatedHybridAndTooNewTemplates() throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("ok.json", template("io.camunda.ok", "Ok", 1, "fine", "^8.9"));
        files.put("deprecated.json", template("io.camunda.old", "Old", 1, "[Deprecated] use the new one", null));
        files.put("slack-hybrid.json", template("io.camunda.slack", "Slack", 1, "fine", null));
        files.put("named.json", template("io.camunda.named", "Kafka (Hybrid)", 1, "fine", null));
        files.put("future.json", template("io.camunda.future", "Future", 1, "fine", "^8.11"));

        assertThat(source.parse(zip(files))).extracting(ConnectorTemplate::id).containsExactly("io.camunda.ok");
    }

    @Test
    void dropsFilesThatAreNotPublishableElementTemplates() throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("notes.txt", "hello");
        files.put("broken.json", "{not json");
        files.put("stub.json", "{\"id\":\"x\",\"version\":1}");
        files.put("array.json", "[" + template("io.camunda.arr", "Arr", 1, "d", null) + "]");
        files.put("noversion.json", "{\"id\":\"x\",\"appliesTo\":[],\"properties\":[]}");
        files.put("good.json", template("io.camunda.good", "Good", 1, "d", null));

        assertThat(source.parse(zip(files))).extracting(ConnectorTemplate::id).containsExactly("io.camunda.good");
    }

    @Test
    void downloadsTheReleaseZipFollowingGitHubsRedirectAndCachesIt() throws IOException {
        byte[] zip = zip(Map.of("rest.json", template("io.camunda.rest", "REST", 1, "d", null)));
        java.util.concurrent.atomic.AtomicInteger downloads = new java.util.concurrent.atomic.AtomicInteger();
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/release", exchange -> {
            byte[] json = ("{\"tag_name\":\"8.10.0\",\"assets\":[{\"name\":\"connectors-bundle-templates-8.10.0.zip\","
                    + "\"browser_download_url\":\"" + base + "/download\"}]}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, json.length);
            exchange.getResponseBody().write(json);
            exchange.close();
        });
        // like GitHub: the asset URL itself only redirects to the real file
        server.createContext("/download", exchange -> {
            exchange.getResponseHeaders().add("Location", base + "/file");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/file", exchange -> {
            downloads.incrementAndGet();
            exchange.sendResponseHeaders(200, zip.length);
            exchange.getResponseBody().write(zip);
            exchange.close();
        });
        server.start();
        try {
            ConnectorTemplateSource fromServer = new ConnectorTemplateSource(base + "/release", "8.10", Clock.systemUTC());

            ConnectorTemplateSource.Snapshot snapshot = fromServer.snapshot();
            fromServer.snapshot();

            assertThat(snapshot.release()).isEqualTo("8.10.0");
            assertThat(snapshot.templates()).extracting(ConnectorTemplate::id).containsExactly("io.camunda.rest");
            assertThat(downloads).hasValue(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sortsByName() throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("b.json", template("io.camunda.b", "beta", 1, "d", null));
        files.put("a.json", template("io.camunda.a", "Alpha", 1, "d", null));

        assertThat(source.parse(zip(files))).extracting(ConnectorTemplate::name).containsExactly("Alpha", "beta");
    }
}
