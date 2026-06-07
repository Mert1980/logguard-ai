package be.vdab.logguard.demo.appender;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.AppenderBase;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;

/**
 * Logback appender that ships ERROR-level log events to OpenSearch
 * using the Kubernetes-style field structure that LogGuard expects.
 *
 * Configured in logback-spring.xml; properties set via Spring Boot's
 * <springProperty> mechanism so they can come from application.yaml.
 */
public class OpenSearchLogAppender extends AppenderBase<ILoggingEvent> {

    private String openSearchUrl;
    private String indexName;
    private String appName;
    private String serviceName;
    private String team;
    private String environment;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    protected void append(ILoggingEvent event) {
        if (!event.getLevel().isGreaterOrEqual(Level.ERROR)) return;

        try {
            String body = buildDocument(event);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(openSearchUrl + "/" + indexName + "/_doc"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            addError("Failed to ship log event to OpenSearch", e);
        }
    }

    private String buildDocument(ILoggingEvent event) throws Exception {
        IThrowableProxy tp = event.getThrowableProxy();

        String exceptionType = tp != null ? tp.getClassName() : "UnknownException";
        String errorMessage  = tp != null && tp.getMessage() != null
                ? tp.getMessage()
                : event.getFormattedMessage();
        String stackTrace    = tp != null ? formatStackTrace(tp) : event.getFormattedMessage();

        ObjectNode root = objectMapper.createObjectNode();
        root.put("@timestamp", Instant.ofEpochMilli(event.getTimeStamp()).toString());

        ObjectNode structured = root.putObject("structured");
        structured.putObject("error")
                .put("type", exceptionType)
                .put("message", errorMessage)
                .put("stack_trace", stackTrace);
        structured.putObject("service").put("name", serviceName);
        structured.putObject("log").put("level", "ERROR");

        ObjectNode kubernetes = root.putObject("kubernetes");
        kubernetes.putObject("labels").put("appName", appName);
        kubernetes.putObject("namespace_labels")
                .put("vdab_be_team", team)
                .put("vdab_be_environment", environment);

        return objectMapper.writeValueAsString(root);
    }

    private String formatStackTrace(IThrowableProxy tp) {
        StringBuilder sb = new StringBuilder();
        sb.append(tp.getClassName()).append(": ").append(tp.getMessage()).append("\n");
        for (StackTraceElementProxy step : tp.getStackTraceElementProxyArray()) {
            sb.append("\tat ").append(step.getSTEAsString()).append("\n");
        }
        if (tp.getCause() != null) {
            sb.append("Caused by: ");
            sb.append(formatStackTrace(tp.getCause()));
        }
        return sb.toString();
    }

    // Setters called by Logback from logback-spring.xml
    public void setOpenSearchUrl(String openSearchUrl) { this.openSearchUrl = openSearchUrl; }
    public void setIndexName(String indexName) { this.indexName = indexName; }
    public void setAppName(String appName) { this.appName = appName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }
    public void setTeam(String team) { this.team = team; }
    public void setEnvironment(String environment) { this.environment = environment; }
}
