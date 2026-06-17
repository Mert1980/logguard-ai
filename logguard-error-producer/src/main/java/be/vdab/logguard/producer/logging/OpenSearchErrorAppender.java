package be.vdab.logguard.producer.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Logback appender that ships ERROR-level log events to OpenSearch as documents with the
 * nested {@code _source.structured.*} / {@code _source.kubernetes.*} / {@code _source.vdab.*}
 * field layout that LogGuard reads.
 *
 * <p>Why a custom appender? The architecture named {@code internetitem:logback-elasticsearch-appender}
 * as the shipping library, but that appender only emits FLAT root-level fields — a property named
 * {@code structured.error.type} becomes a single flat key, not the nested object LogGuard expects.
 * This appender builds the genuinely nested document instead. See the story Dev Notes
 * "THE NESTING LANDMINE". The architecture's library choice should be updated to match.</p>
 *
 * <p>The document is assembled with manual JSON string building (no Jackson) so the fixture does
 * not depend on whether the platform ships Jackson 2 ({@code com.fasterxml.jackson}) or Jackson 3
 * ({@code tools.jackson}) — Spring Boot 4 ships Jackson 3.</p>
 */
public class OpenSearchErrorAppender extends AppenderBase<ILoggingEvent> {

    private static final DateTimeFormatter INDEX_DATE =
            DateTimeFormatter.ofPattern("yyyy.MM.dd").withZone(ZoneOffset.UTC);

    // ---- configurable via logback-spring.xml ----
    private String url = "http://localhost:9200";
    private String indexPrefix = "logstash-app-openshift-application-springboot_error";
    private String appName = "logguard-error-producer";
    private String team = "backend-team";
    private String environment = "local";
    private String defaultServiceName = "orgbeheer-service";
    private String defaultAuthorization = "cn=TESTUSER,ou=users,ou=intern,O=VDAB";

    private HttpClient httpClient;

    @Override
    public void start() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        super.start();
    }

    @Override
    protected void append(ILoggingEvent event) {
        try {
            String body = buildDocument(event);
            String index = indexPrefix + "_" + INDEX_DATE.format(Instant.now());
            // refresh=true makes the document immediately searchable so the integration-gate
            // verification (curl _search right after the POST) is deterministic.
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url + "/" + index + "/_doc?refresh=true"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                addWarn("OpenSearch rejected error document (HTTP " + response.statusCode()
                        + "): " + response.body());
            }
        } catch (Exception e) {
            // A logging-pipeline failure must never crash the fixture app.
            addError("Failed to ship error document to OpenSearch at " + url, e);
        }
    }

    private String buildDocument(ILoggingEvent event) {
        IThrowableProxy throwable = event.getThrowableProxy();
        String errorType = throwable != null ? throwable.getClassName() : "UnknownError";
        String errorMessage = throwable != null ? throwable.getMessage() : event.getFormattedMessage();
        String stackTrace = throwable != null ? ThrowableProxyUtil.asString(throwable) : "";

        StringBuilder json = new StringBuilder(stackTrace.length() + 512);
        json.append('{');
        field(json, "@timestamp", Instant.ofEpochMilli(event.getTimeStamp()).toString());
        json.append(',');
        json.append("\"structured\":{");
        json.append("\"error\":{");
        field(json, "type", errorType);
        json.append(',');
        field(json, "message", errorMessage);
        json.append(',');
        field(json, "stack_trace", stackTrace);
        json.append("},");
        json.append("\"service\":{");
        field(json, "name", serviceName(event));
        json.append("},");
        json.append("\"log\":{");
        field(json, "level", event.getLevel().toString());
        json.append("}");
        json.append("},");
        json.append("\"kubernetes\":{");
        json.append("\"labels\":{");
        field(json, "appName", appName);
        json.append("},");
        json.append("\"namespace_labels\":{");
        field(json, "vdab_be_team", team);
        json.append(',');
        field(json, "vdab_be_environment", environment);
        json.append("}");
        json.append("},");
        json.append("\"vdab\":{");
        field(json, "authorization", authorization(event));
        json.append("}");
        json.append('}');
        return json.toString();
    }

    /** Appends {@code "key":"escaped-value"} (or {@code "key":null} when value is null). */
    private static void field(StringBuilder sb, String key, String value) {
        sb.append('"').append(key).append("\":");
        if (value == null) {
            sb.append("null");
        } else {
            sb.append('"');
            escape(sb, value);
            sb.append('"');
        }
    }

    /** Minimal RFC 8259 JSON string escaping. */
    private static void escape(StringBuilder sb, String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
    }

    private String serviceName(ILoggingEvent event) {
        String fromMdc = event.getMDCPropertyMap().get("serviceName");
        return (fromMdc != null && !fromMdc.isBlank()) ? fromMdc : defaultServiceName;
    }

    private String authorization(ILoggingEvent event) {
        String fromMdc = event.getMDCPropertyMap().get("vdabAuthorization");
        return (fromMdc != null && !fromMdc.isBlank()) ? fromMdc : defaultAuthorization;
    }

    // ---- setters bound from logback-spring.xml child elements ----
    public void setUrl(String url) { this.url = url; }
    public void setIndexPrefix(String indexPrefix) { this.indexPrefix = indexPrefix; }
    public void setAppName(String appName) { this.appName = appName; }
    public void setTeam(String team) { this.team = team; }
    public void setEnvironment(String environment) { this.environment = environment; }
    public void setDefaultServiceName(String defaultServiceName) { this.defaultServiceName = defaultServiceName; }
    public void setDefaultAuthorization(String defaultAuthorization) { this.defaultAuthorization = defaultAuthorization; }
}
