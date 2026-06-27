package edu.upc.campusspace.composer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ComposerServer {
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4))
            .build();
    private static final Pattern FIELD_PATTERN_TEMPLATE = Pattern.compile("\"%s\":\"([^\"]*)\"");
    private static final Pattern NUMBER_PATTERN_TEMPLATE = Pattern.compile("\"%s\":([0-9.]+)");

    public static void main(String[] args) throws Exception {
        int port = readPort(4571);
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/health", exchange -> {
            logRequest(exchange);
            sendJson(exchange, 200, healthJson(port));
        });
        server.createContext("/debug-config", exchange -> {
            logRequest(exchange);
            sendJson(exchange, 200, configJson());
        });
        server.createContext("/reservas-compuestas", ComposerServer::handleCompositeReservations);
        server.createContext("/dashboard", ComposerServer::handleDashboard);
        server.createContext("/dashboard-html", ComposerServer::handleDashboardHtml);
        server.createContext("/correos-compuestos", ComposerServer::handleCompositeCorreos);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        System.out.printf("CampusSpace Composer Service iniciado en puerto %d%n", port);
        System.out.println("Endpoints: /health, /debug-config, /reservas-compuestas, /dashboard, /dashboard-html, /correos-compuestos");
        System.out.println("SOURCE_SERVICE_URL=" + sourceBaseUrl());
    }

    private static void handleCompositeReservations(HttpExchange exchange) throws IOException {
        logRequest(exchange);
        String query = exchange.getRequestURI().getRawQuery();
        String suffix = query == null || query.isBlank() ? "" : "?" + query;
        try {
            String reservations = fetch(sourceBaseUrl() + "/reservas" + suffix);
            String rooms = fetch(sourceBaseUrl() + "/salas");
            String routes = fetch(sourceBaseUrl() + "/rutas");
            String json = "{"
                    + "\"composer\":\"campusspace-composer\","
                    + "\"sourceService\":\"" + escape(sourceBaseUrl()) + "\","
                    + "\"generatedView\":\"RESERVAS_COMPUESTAS\","
                    + "\"reservas\":" + reservations + ","
                    + "\"salas\":" + rooms + ","
                    + "\"externalRoutes\":" + routes
                    + "}";
            sendJson(exchange, 200, json);
        } catch (Exception exception) {
            sendJson(exchange, 503, errorJson("No se pudo componer la informacion", exception));
        }
    }

    private static void handleCompositeCorreos(HttpExchange exchange) throws IOException {
        logRequest(exchange);
        try {
            String correos = fetch(sourceBaseUrl() + "/correos");
            String routes = fetch(sourceBaseUrl() + "/rutas");

            String json = "{"
                    + "\"composer\":\"campusspace-composer\","
                    + "\"sourceService\":\"" + escape(sourceBaseUrl()) + "\","
                    + "\"generatedView\":\"CORREOS_COMPUESTOS\","
                    + "\"sourceEndpoint\":\"/correos\","
                    + "\"correos\":" + correos + ","
                    + "\"externalRoutes\":" + routes
                    + "}";
            sendJson(exchange, 200, json);
        } catch (Exception exception) {
            sendJson(exchange, 503, errorJson("No se pudo componer la informacion de correos", exception));
        }
    }

    private static void handleDashboard(HttpExchange exchange) throws IOException {
        logRequest(exchange);
        try {
            Dashboard dashboard = buildDashboard();
            sendJson(exchange, 200, dashboard.toJson());
        } catch (Exception exception) {
            sendJson(exchange, 503, errorJson("No se pudo generar el dashboard", exception));
        }
    }

    private static void handleDashboardHtml(HttpExchange exchange) throws IOException {
        logRequest(exchange);
        try {
            Dashboard dashboard = buildDashboard();
            String template = readResource("composer-template.html");
            String content = "<div class='grid'>"
                    + metric("Reservas", dashboard.totalReservas)
                    + metric("Confirmadas", dashboard.confirmadas)
                    + metric("Observadas", dashboard.observadas)
                    + metric("Ocupacion promedio", dashboard.promedioOcupacion + "%")
                    + "</div>"
                    + "<h2>Distribucion por sede</h2><pre>" + escapeHtml(dashboard.sedes.toString()) + "</pre>"
                    + "<h2>Distribucion por tipo de sala</h2><pre>" + escapeHtml(dashboard.tipos.toString()) + "</pre>"
                    + "<h2>Fuente utilizada</h2><pre>" + escapeHtml(sourceBaseUrl()) + "</pre>";
            sendHtml(exchange, 200, template.replace("{{CONTENT}}", content));
        } catch (Exception exception) {
            sendHtml(exchange, 503, "<h1>Error</h1><p>" + escapeHtml(exception.getMessage()) + "</p>");
        }
    }


    private static void logRequest(HttpExchange exchange) {
        System.out.printf("[%s] %s %s%n", java.time.LocalDateTime.now(), exchange.getRequestMethod(), exchange.getRequestURI());
    }

    private static Dashboard buildDashboard() throws Exception {
        String reservations = fetch(sourceBaseUrl() + "/reservas");
        String rooms = fetch(sourceBaseUrl() + "/salas");
        String routes = fetch(sourceBaseUrl() + "/rutas");

        Dashboard dashboard = new Dashboard();
        dashboard.sourceService = sourceBaseUrl();
        dashboard.totalReservas = countOccurrences(reservations, "\"id\":\"RSV-");
        dashboard.totalSalas = countOccurrences(rooms, "\"id\":\"SALA-");
        dashboard.totalExternalSites = countOccurrences(routes, "https://");
        dashboard.confirmadas = countField(reservations, "estado", "CONFIRMADA");
        dashboard.observadas = countField(reservations, "estado", "OBSERVADA");
        dashboard.pendientes = countField(reservations, "estado", "PENDIENTE");
        dashboard.sedes = countByTextField(reservations, "sede");
        dashboard.tipos = countByTextField(reservations, "tipo");
        dashboard.promedioOcupacion = averageNumberField(reservations, "ocupacionPct");
        dashboard.recomendacionOperativa = dashboard.observadas > 0
                ? "Revisar reservas observadas: posible sobrecupo o ambiente no adecuado."
                : "No se detectaron reservas observadas.";
        return dashboard;
    }

    private static String metric(String label, Object value) {
        return "<div class='metric'><span>" + escapeHtml(label) + "</span><strong>" + escapeHtml(String.valueOf(value)) + "</strong></div>";
    }

    private static String fetch(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .GET()
                .header("Accept", "application/json")
                .build();
        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " al consultar " + url);
        }
        return response.body();
    }

    private static Map<String, Integer> countByTextField(String json, String fieldName) {
        Map<String, Integer> result = new LinkedHashMap<>();
        Matcher matcher = Pattern.compile(String.format(FIELD_PATTERN_TEMPLATE.pattern(), Pattern.quote(fieldName))).matcher(json);
        while (matcher.find()) {
            String key = matcher.group(1);
            result.put(key, result.getOrDefault(key, 0) + 1);
        }
        return result;
    }

    private static int countField(String json, String fieldName, String expectedValue) {
        Matcher matcher = Pattern.compile(String.format(FIELD_PATTERN_TEMPLATE.pattern(), Pattern.quote(fieldName))).matcher(json);
        int count = 0;
        while (matcher.find()) {
            if (expectedValue.equalsIgnoreCase(matcher.group(1))) count++;
        }
        return count;
    }

    private static double averageNumberField(String json, String fieldName) {
        Matcher matcher = Pattern.compile(String.format(NUMBER_PATTERN_TEMPLATE.pattern(), Pattern.quote(fieldName))).matcher(json);
        double total = 0;
        int count = 0;
        while (matcher.find()) {
            total += Double.parseDouble(matcher.group(1));
            count++;
        }
        if (count == 0) return 0;
        return Math.round((total / count) * 100.0) / 100.0;
    }

    private static int countOccurrences(String text, String token) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(token, index)) >= 0) {
            count++;
            index += token.length();
        }
        return count;
    }

    private static String sourceBaseUrl() {
        String env = System.getenv("SOURCE_SERVICE_URL");
        if (env == null || env.isBlank()) env = System.getProperty("SOURCE_SERVICE_URL");
        return env == null || env.isBlank() ? "http://localhost:4570" : env.replaceAll("/+$", "");
    }

    private static int readPort(int fallback) {
        String env = System.getenv("PORT");
        if (env == null || env.isBlank()) env = System.getProperty("PORT");
        try {
            return env == null ? fallback : Integer.parseInt(env);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static String healthJson(int port) {
        return "{\"service\":\"campusspace-composer\",\"status\":\"UP\",\"port\":" + port +
                ",\"sourceService\":\"" + escape(sourceBaseUrl()) + "\"}";
    }

    private static String configJson() {
        return "{\"SOURCE_SERVICE_URL\":\"" + escape(sourceBaseUrl()) + "\",\"requiredForDockerNetwork\":true}";
    }

    private static String errorJson(String message, Exception exception) {
        return "{\"error\":\"" + escape(message) + "\",\"detail\":\"" + escape(exception.getMessage()) + "\",\"sourceService\":\"" + escape(sourceBaseUrl()) + "\"}";
    }

    private static String readResource(String name) throws IOException {
        try (InputStream inputStream = ComposerServer.class.getClassLoader().getResourceAsStream(name)) {
            if (inputStream == null) return "";
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void sendJson(HttpExchange exchange, int statusCode, String body) throws IOException {
        send(exchange, statusCode, body, "application/json; charset=utf-8");
    }

    private static void sendHtml(HttpExchange exchange, int statusCode, String body) throws IOException {
        send(exchange, statusCode, body, "text/html; charset=utf-8");
    }

    private static void send(HttpExchange exchange, int statusCode, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String escapeHtml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static class Dashboard {
        String sourceService;
        int totalReservas;
        int totalSalas;
        int totalExternalSites;
        int confirmadas;
        int observadas;
        int pendientes;
        double promedioOcupacion;
        Map<String, Integer> sedes = new LinkedHashMap<>();
        Map<String, Integer> tipos = new LinkedHashMap<>();
        String recomendacionOperativa;

        String toJson() {
            return "{"
                    + "\"composer\":\"campusspace-composer\","
                    + "\"sourceService\":\"" + escape(sourceService) + "\","
                    + "\"totalReservas\":" + totalReservas + ","
                    + "\"totalSalas\":" + totalSalas + ","
                    + "\"totalExternalSites\":" + totalExternalSites + ","
                    + "\"confirmadas\":" + confirmadas + ","
                    + "\"observadas\":" + observadas + ","
                    + "\"pendientes\":" + pendientes + ","
                    + "\"promedioOcupacion\":" + promedioOcupacion + ","
                    + "\"porSede\":" + mapToJson(sedes) + ","
                    + "\"porTipoSala\":" + mapToJson(tipos) + ","
                    + "\"recomendacionOperativa\":\"" + escape(recomendacionOperativa) + "\""
                    + "}";
        }

        private String mapToJson(Map<String, Integer> values) {
            StringBuilder builder = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Integer> entry : values.entrySet()) {
                if (!first) builder.append(',');
                builder.append('"').append(escape(entry.getKey())).append("\":").append(entry.getValue());
                first = false;
            }
            builder.append('}');
            return builder.toString();
        }
    }
}
