package be.vdab.logguard.infrastructure.opensearch;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Deserialization DTO mirroring the nested {@code _source} a companion document carries. Bound by the
 * OpenSearch client's {@code JacksonJsonpMapper}, which is <b>Jackson 2</b> — hence
 * {@code com.fasterxml.jackson.*} annotations (NOT {@code tools.jackson.*}; Spring Boot 4's Jackson 3
 * must not be used here). {@code @JsonIgnoreProperties(ignoreUnknown=true)} on every level tolerates the
 * many extra fields real documents carry (e.g. {@code structured.log}, {@code process}, raw message).
 *
 * <p>{@code @timestamp} is kept as a String; {@code OpenSearchAdapter} parses it to {@link java.time.Instant}
 * so the JsonpMapper needs no Jackson date module.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class OpenSearchErrorDocument {

    @JsonProperty("@timestamp")
    public String timestamp;
    public Structured structured;
    public Kubernetes kubernetes;
    public Vdab vdab;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Structured {
        public Error error;
        public Service service;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Error {
        public String type;
        public String message;
        @JsonProperty("stack_trace")
        public String stackTrace;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Service {
        public String name;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Kubernetes {
        public Labels labels;
        @JsonProperty("namespace_labels")
        public NamespaceLabels namespaceLabels;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Labels {
        public String appName;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class NamespaceLabels {
        @JsonProperty("vdab_be_team")
        public String team;
        @JsonProperty("vdab_be_environment")
        public String environment;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Vdab {
        public String authorization;
    }
}
