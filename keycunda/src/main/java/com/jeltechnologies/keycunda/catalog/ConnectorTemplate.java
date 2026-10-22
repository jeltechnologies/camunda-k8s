package com.jeltechnologies.keycunda.catalog;

import java.util.List;

/**
 * One connector template (a Camunda element template JSON) that can be published to the Camunda Hub
 * Catalog. {@code content} is the original JSON text, sent to Hub unchanged.
 */
public record ConnectorTemplate(String id, String name, String description, String documentationRef, int version,
        String content, List<String> keywords) {
}
