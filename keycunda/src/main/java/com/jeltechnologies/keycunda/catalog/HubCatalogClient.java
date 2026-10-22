package com.jeltechnologies.keycunda.catalog;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The Catalog of Camunda Hub (REST API v2 at {@code {hub}/api/v2/catalog/...}): search, ingest and
 * delete. Ingestion is full-state - Hub unpublishes every previously ingested asset that is missing
 * from the request - so callers must always send the complete set they want published
 * ({@link CatalogPublisher} computes it).
 */
@Component
public class HubCatalogClient {

    private static final Logger log = LoggerFactory.getLogger(HubCatalogClient.class);

    private static final int PAGE_LIMIT = 100;
    // Hub's default ingestion limits (raisable on self-managed): 5,000 files, 20 MB per request, 4 MB per file.
    static final int MAX_FILES = 5000;
    static final long MAX_TOTAL_BYTES = 20L * 1024 * 1024;
    static final long MAX_FILE_BYTES = 4L * 1024 * 1024;

    /** Supplies the bearer token for Hub's API (see {@link HubTokenProvider}). */
    @FunctionalInterface
    public interface BearerTokenSource {
        String token() throws IOException;
    }

    /** A Catalog asset as reported by search; the template content is not part of the result. */
    public record CatalogAsset(String assetKey, String resourceId, String name, int latestVersion, boolean unpublished) {
    }

    /** One asset to ingest: the element template JSON and its README, with the template's file name. */
    public record CatalogTemplate(String templateFilename, String templateContent, String readmeContent) {
        String readmeFilename() {
            return templateFilename.replaceAll("\\.json$", "") + ".md";
        }
    }

    /** An error answer from Hub, with the HTTP status and the Problem Detail text. */
    public static class HubApiException extends IOException {
        private final int status;

        public HubApiException(int status, String message, Throwable cause) {
            super(message, cause);
            this.status = status;
        }

        public int getStatus() {
            return status;
        }
    }

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String apiUrl;
    private final BearerTokenSource tokens;
    private final RestClient client;

    @Autowired
    public HubCatalogClient(@Value("${keycunda.hub.base-url:http://camunda-web-modeler-restapi/hub}") String baseUrl,
            HubTokenProvider tokens) {
        this(baseUrl, (BearerTokenSource) tokens);
    }

    HubCatalogClient(String baseUrl, BearerTokenSource tokens) {
        this.apiUrl = baseUrl.replaceAll("/+$", "") + "/api/v2";
        this.tokens = tokens;
        // HTTP/1.1 and a buffered body: the in-cluster Hub speaks plain HTTP, and a multipart upload
        // needs a Content-Length rather than chunked encoding.
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        this.client = RestClient.builder()
                .requestFactory(new BufferingClientHttpRequestFactory(new JdkClientHttpRequestFactory(http)))
                .build();
    }

    /** All Catalog assets, published and unpublished. */
    public List<CatalogAsset> listAssets() throws IOException {
        List<CatalogAsset> assets = new ArrayList<>();
        int from = 0;
        while (true) {
            Map<String, Object> page = new LinkedHashMap<>();
            page.put("from", from);
            page.put("limit", PAGE_LIMIT);
            JsonNode response = json(HttpMethod.POST, "/catalog/assets/search", Map.of("page", page));
            JsonNode items = response.path("items");
            if (!items.isArray() || items.isEmpty()) {
                break;
            }
            for (JsonNode item : items) {
                assets.add(new CatalogAsset(item.path("assetKey").asText(""), item.path("resourceId").asText(""),
                        item.path("name").asText(""), item.path("latestVersion").asInt(0),
                        item.path("unpublished").asBoolean(false)));
            }
            from += items.size();
            long total = response.path("page").path("totalItems").asLong(-1);
            if (items.size() < PAGE_LIMIT || (total >= 0 && from >= total)) {
                break;
            }
        }
        return assets;
    }

    public void deleteAsset(CatalogAsset asset) throws IOException {
        exchange(HttpMethod.DELETE, "/catalog/assets/" + asset.assetKey(), null, null);
        log.info("Deleted Catalog asset '{}' ({})", asset.name(), asset.resourceId());
    }

    /**
     * Ingests the given templates as the complete desired Catalog (PUT, multipart: one "template" and
     * one "readme" part per asset). Hub validates and applies everything in one transaction, so a
     * failure means nothing changed.
     */
    public void ingest(List<CatalogTemplate> templates) throws IOException {
        if (templates.isEmpty()) {
            throw new IOException("Hub rejects an empty Catalog submission; there is nothing to publish");
        }
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        long total = 0;
        for (CatalogTemplate t : templates) {
            byte[] template = t.templateContent().getBytes(StandardCharsets.UTF_8);
            byte[] readme = t.readmeContent().getBytes(StandardCharsets.UTF_8);
            if (template.length > MAX_FILE_BYTES) {
                throw new IOException("Connector template " + t.templateFilename() + " is " + (template.length / 1024)
                        + " KB, over Hub's limit of " + (MAX_FILE_BYTES / 1024 / 1024) + " MB per file");
            }
            total += template.length + readme.length;
            parts.add("template", part(t.templateFilename(), template, MediaType.APPLICATION_JSON));
            parts.add("readme", part(t.readmeFilename(), readme, MediaType.parseMediaType("text/markdown")));
        }
        if (templates.size() * 2 > MAX_FILES || total > MAX_TOTAL_BYTES) {
            throw new IOException("The submission (" + templates.size() + " connector templates, " + (total / 1024 / 1024)
                    + " MB) exceeds Hub's ingestion limits of " + (MAX_FILES / 2) + " connector templates and "
                    + (MAX_TOTAL_BYTES / 1024 / 1024) + " MB per request. Select fewer, or raise the limits in Hub.");
        }
        exchange(HttpMethod.PUT, "/catalog/assets/ingestion", parts, MediaType.MULTIPART_FORM_DATA);
        log.info("Ingested {} connector templates ({} KB) into the Catalog", templates.size(), total / 1024);
    }

    private static HttpEntity<ByteArrayResource> part(String filename, byte[] content, MediaType type) {
        ByteArrayResource resource = new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(type);
        return new HttpEntity<>(resource, headers);
    }

    private JsonNode json(HttpMethod method, String path, Object body) throws IOException {
        String response = exchange(method, path, body, MediaType.APPLICATION_JSON);
        if (response == null || response.isBlank()) {
            return objectMapper.createObjectNode();
        }
        String start = response.stripLeading();
        if (!start.startsWith("{") && !start.startsWith("[")) {
            throw new IOException("Camunda Hub answered with a web page instead of its API. Check keycunda.hub.base-url "
                    + "(Hub's address without /api/v2).");
        }
        return objectMapper.readTree(response);
    }

    private String exchange(HttpMethod method, String path, Object body, MediaType contentType) throws IOException {
        String url = apiUrl + path;
        try {
            RestClient.RequestBodySpec request = client.method(method).uri(url)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.token());
            if (body != null) {
                request.contentType(contentType).body(body);
            }
            return request.retrieve().body(String.class);
        } catch (RestClientResponseException e) {
            throw toException(e);
        } catch (org.springframework.web.client.ResourceAccessException e) {
            throw new IOException("Camunda Hub is not reachable at " + url + " (is it still starting?): " + e.getMessage(), e);
        }
    }

    private static HubApiException toException(RestClientResponseException e) {
        int status = e.getStatusCode().value();
        String detail = problemDetail(e.getResponseBodyAsString());
        String message = "Hub answered HTTP " + status + (detail.isBlank() ? "" : ": " + detail);
        if (status == 401 || status == 403) {
            message += ". The Keycunda service client needs the audience 'web-modeler-public-api' and create, read, "
                    + "update and delete permissions on the Hub API in Identity.";
        }
        return new HubApiException(status, message, e);
    }

    /** "title: detail" from an RFC 9457 Problem Detail body, else the raw text (cut at 500 characters). */
    static String problemDetail(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            JsonNode node = new ObjectMapper().readTree(body);
            String title = node.path("title").asText("");
            String detail = node.path("detail").asText("");
            if (!title.isBlank() || !detail.isBlank()) {
                return (title + (title.isBlank() || detail.isBlank() ? "" : ": ") + detail).trim();
            }
        } catch (Exception ignored) {
            // not JSON: fall through to the raw text
        }
        return body.length() > 500 ? body.substring(0, 500) : body;
    }
}
