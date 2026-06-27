package edu.upc.campusspace.source;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.datafaker.Faker;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class SourceServer {
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final List<Room> ROOMS = loadRooms();
    private static final List<Reservation> RESERVATIONS = seedReservations();
    private static final List<Correo> CORREOS = seedCorreos();

    public static void main(String[] args) throws Exception {
        int port = readPort(4570);
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/health", exchange -> {
            logRequest(exchange);
            sendJson(exchange, 200, healthJson(port));
        });
        server.createContext("/salas", SourceServer::handleRooms);
        server.createContext("/reservas", SourceServer::handleReservations);
        server.createContext("/html", SourceServer::handleHtml);
        server.createContext("/rutas", SourceServer::handleRoutes);
        server.createContext("/correos", SourceServer::handleCorreos);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        System.out.printf("CampusSpace Source Service iniciado en puerto %d%n", port);
        System.out.println("Endpoints: /health, /salas, /reservas, /html, /rutas, /correos");
    }

    private static void handleRooms(HttpExchange exchange) throws IOException {
        logRequest(exchange);
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"error\":\"Metodo no permitido\"}");
            return;
        }
        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        String sede = query.getOrDefault("sede", "");
        String tipo = query.getOrDefault("tipo", "");
        int minCapacidad = parseInt(query.get("minCapacidad"), 0);
        List<Room> filtered = ROOMS.stream()
                .filter(room -> sede.isBlank() || room.sede.equalsIgnoreCase(sede))
                .filter(room -> tipo.isBlank() || room.tipo.equalsIgnoreCase(tipo))
                .filter(room -> room.capacidad >= minCapacidad)
                .sorted(Comparator.comparing((Room room) -> room.sede).thenComparing(room -> room.nombre))
                .toList();
        sendJson(exchange, 200, roomsJson(filtered));
    }

    private static void handleReservations(HttpExchange exchange) throws IOException {
        logRequest(exchange);
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"error\":\"Metodo no permitido\"}");
            return;
        }
        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        String sede = query.getOrDefault("sede", "");
        String estado = query.getOrDefault("estado", "");
        int limite = parseInt(query.get("limit"), RESERVATIONS.size());
        List<Reservation> filtered = RESERVATIONS.stream()
                .filter(reservation -> estado.isBlank() || reservation.estado.equalsIgnoreCase(estado))
                .filter(reservation -> sede.isBlank() || findRoom(reservation.salaId).map(room -> room.sede.equalsIgnoreCase(sede)).orElse(false))
                .limit(Math.max(1, limite))
                .toList();
        sendJson(exchange, 200, reservationsJson(filtered));
    }

    private static void handleHtml(HttpExchange exchange) throws IOException {
        logRequest(exchange);
        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        String sede = query.getOrDefault("sede", "");
        String estado = query.getOrDefault("estado", "");
        List<Reservation> filtered = RESERVATIONS.stream()
                .filter(reservation -> estado.isBlank() || reservation.estado.equalsIgnoreCase(estado))
                .filter(reservation -> sede.isBlank() || findRoom(reservation.salaId).map(room -> room.sede.equalsIgnoreCase(sede)).orElse(false))
                .toList();
        String template = readResource("template.html");
        String content = htmlContent(filtered);
        sendHtml(exchange, 200, template.replace("{{CONTENT}}", content));
    }

    private static void handleRoutes(HttpExchange exchange) throws IOException {
        logRequest(exchange);
        String rutas = readResource("rutas.txt");
        String[] lines = rutas.split("\\R");
        StringBuilder json = new StringBuilder();
        json.append("{\"total\":").append(lines.length).append(",\"externalSites\":[");
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) json.append(',');
            json.append('"').append(escape(lines[i])).append('"');
        }
        json.append("]}");
        sendJson(exchange, 200, json.toString());
    }

    private static void handleCorreos(HttpExchange exchange) throws IOException {
        logRequest(exchange);
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"error\":\"Metodo no permitido\"}");
            return;
        }

        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        int limite = parseInt(query.get("limit"), CORREOS.size());
        String format = query.getOrDefault("format", "json");

        List<Correo> filtered = CORREOS.stream()
                .limit(Math.max(1, Math.min(limite, CORREOS.size())))
                .toList();

        if ("html".equalsIgnoreCase(format)) {
            sendHtml(exchange, 200, correosHtml(filtered));
        } else {
            sendJson(exchange, 200, correosJson(filtered));
        }
    }


    private static void logRequest(HttpExchange exchange) {
        System.out.printf("[%s] %s %s%n", LocalDateTime.now(), exchange.getRequestMethod(), exchange.getRequestURI());
    }

    private static String healthJson(int port) {
        return "{\"service\":\"campusspace-source\",\"status\":\"UP\",\"port\":" + port +
                ",\"rooms\":" + ROOMS.size() + ",\"reservations\":" + RESERVATIONS.size() +
                ",\"correos\":" + CORREOS.size() + "}";
    }

    private static String roomsJson(List<Room> rooms) {
        StringBuilder json = new StringBuilder();
        json.append("{\"total\":").append(rooms.size()).append(",\"items\":[");
        for (int i = 0; i < rooms.size(); i++) {
            if (i > 0) json.append(',');
            Room room = rooms.get(i);
            json.append("{\"id\":\"").append(escape(room.id)).append("\",")
                    .append("\"sede\":\"").append(escape(room.sede)).append("\",")
                    .append("\"nombre\":\"").append(escape(room.nombre)).append("\",")
                    .append("\"tipo\":\"").append(escape(room.tipo)).append("\",")
                    .append("\"capacidad\":").append(room.capacidad).append(',')
                    .append("\"recursos\":[");
            for (int j = 0; j < room.recursos.size(); j++) {
                if (j > 0) json.append(',');
                json.append('"').append(escape(room.recursos.get(j))).append('"');
            }
            json.append("]}");
        }
        json.append("]}");
        return json.toString();
    }

    private static String reservationsJson(List<Reservation> reservations) {
        StringBuilder json = new StringBuilder();
        json.append("{\"total\":").append(reservations.size()).append(",\"items\":[");
        for (int i = 0; i < reservations.size(); i++) {
            if (i > 0) json.append(',');
            Reservation reservation = reservations.get(i);
            Room room = findRoom(reservation.salaId).orElse(ROOMS.get(0));
            double ocupacionPct = Math.round((reservation.participantes * 10000.0 / room.capacidad)) / 100.0;
            json.append("{\"id\":\"").append(escape(reservation.id)).append("\",")
                    .append("\"salaId\":\"").append(escape(reservation.salaId)).append("\",")
                    .append("\"sede\":\"").append(escape(room.sede)).append("\",")
                    .append("\"sala\":\"").append(escape(room.nombre)).append("\",")
                    .append("\"tipo\":\"").append(escape(room.tipo)).append("\",")
                    .append("\"responsable\":\"").append(escape(reservation.responsable)).append("\",")
                    .append("\"curso\":\"").append(escape(reservation.curso)).append("\",")
                    .append("\"inicio\":\"").append(escape(reservation.inicio.format(DATE_TIME))).append("\",")
                    .append("\"fin\":\"").append(escape(reservation.fin.format(DATE_TIME))).append("\",")
                    .append("\"capacidad\":").append(room.capacidad).append(',')
                    .append("\"participantes\":").append(reservation.participantes).append(',')
                    .append("\"ocupacionPct\":").append(ocupacionPct).append(',')
                    .append("\"estado\":\"").append(escape(reservation.estado)).append("\",")
                    .append("\"prioridad\":\"").append(escape(reservation.prioridad)).append("\"}");
        }
        json.append("]}");
        return json.toString();
    }

    private static String htmlContent(List<Reservation> reservations) {
        Map<String, Long> bySede = reservations.stream()
                .collect(Collectors.groupingBy(reservation -> findRoom(reservation.salaId).map(room -> room.sede).orElse("Sin sede"), LinkedHashMap::new, Collectors.counting()));
        StringBuilder html = new StringBuilder();
        html.append("<div class='card'><strong>Total de reservas:</strong> ").append(reservations.size()).append("<br>");
        bySede.forEach((sede, count) -> html.append("<span class='badge'>").append(escapeHtml(sede)).append(": ").append(count).append("</span> "));
        html.append("</div>");
        html.append("<table><thead><tr><th>ID</th><th>Sede</th><th>Sala</th><th>Curso</th><th>Inicio</th><th>Participantes</th><th>Estado</th></tr></thead><tbody>");
        for (Reservation reservation : reservations) {
            Room room = findRoom(reservation.salaId).orElse(ROOMS.get(0));
            html.append("<tr>")
                    .append("<td>").append(escapeHtml(reservation.id)).append("</td>")
                    .append("<td>").append(escapeHtml(room.sede)).append("</td>")
                    .append("<td>").append(escapeHtml(room.nombre)).append("</td>")
                    .append("<td>").append(escapeHtml(reservation.curso)).append("</td>")
                    .append("<td>").append(escapeHtml(reservation.inicio.format(DATE_TIME))).append("</td>")
                    .append("<td>").append(reservation.participantes).append("/").append(room.capacidad).append("</td>")
                    .append("<td>").append(escapeHtml(reservation.estado)).append("</td>")
                    .append("</tr>");
        }
        html.append("</tbody></table>");
        return html.toString();
    }

    private static java.util.Optional<Room> findRoom(String id) {
        return ROOMS.stream().filter(room -> room.id.equals(id)).findFirst();
    }

    private static List<Room> loadRooms() {
        List<Room> rooms = new ArrayList<>();
        try (InputStream inputStream = SourceServer.class.getClassLoader().getResourceAsStream("salas.csv")) {
            if (inputStream == null) {
                throw new IllegalStateException("No se encontro salas.csv en resources");
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                reader.lines().skip(1).forEach(line -> {
                    String[] parts = line.split(",", -1);
                    if (parts.length >= 6) {
                        rooms.add(new Room(parts[0], parts[1], parts[2], parts[3], Integer.parseInt(parts[4]), List.of(parts[5].split("\\|"))));
                    }
                });
            }
        } catch (IOException e) {
            throw new IllegalStateException("Error leyendo salas.csv", e);
        }
        return rooms;
    }

    private static List<Reservation> seedReservations() {
        LocalDate base = LocalDate.now().plusDays(1);
        return List.of(
                new Reservation("RSV-0001", "SALA-102", "Lucia Ramos", "Fundamentos de Arquitectura", base.atTime(8, 0), base.atTime(10, 0), 30, "CONFIRMADA", "ALTA"),
                new Reservation("RSV-0002", "SALA-201", "Mateo Flores", "Taller de Proyecto", base.atTime(10, 0), base.atTime(12, 0), 15, "CONFIRMADA", "MEDIA"),
                new Reservation("RSV-0003", "SALA-302", "Valeria Torres", "Machine Learning", base.atTime(14, 0), base.atTime(16, 0), 36, "OBSERVADA", "ALTA"),
                new Reservation("RSV-0004", "SALA-403", "Diego Salazar", "Sprint Review", base.atTime(16, 0), base.atTime(18, 0), 11, "CONFIRMADA", "BAJA"),
                new Reservation("RSV-0005", "SALA-401", "Camila Herrera", "Charla Empleabilidad", base.plusDays(1).atTime(9, 0), base.plusDays(1).atTime(11, 0), 76, "CONFIRMADA", "ALTA"),
                new Reservation("RSV-0006", "SALA-303", "Sebastian Cruz", "Asesoria de Tesis", base.plusDays(1).atTime(11, 0), base.plusDays(1).atTime(12, 0), 9, "OBSERVADA", "MEDIA"),
                new Reservation("RSV-0007", "SALA-202", "Fernanda Soto", "DevOps", base.plusDays(1).atTime(15, 0), base.plusDays(1).atTime(17, 0), 26, "CONFIRMADA", "ALTA"),
                new Reservation("RSV-0008", "SALA-101", "Alonso Medina", "Design Thinking", base.plusDays(2).atTime(8, 0), base.plusDays(2).atTime(10, 0), 18, "PENDIENTE", "MEDIA"),
                new Reservation("RSV-0009", "SALA-402", "Renata Leon", "IoT Aplicado", base.plusDays(2).atTime(10, 0), base.plusDays(2).atTime(12, 0), 24, "CONFIRMADA", "MEDIA"),
                new Reservation("RSV-0010", "SALA-203", "Nicolas Paredes", "Gestion de Producto", base.plusDays(2).atTime(12, 0), base.plusDays(2).atTime(13, 30), 12, "CONFIRMADA", "BAJA"),
                new Reservation("RSV-0011", "SALA-301", "Ana Gutierrez", "Experiencia de Usuario", base.plusDays(3).atTime(9, 0), base.plusDays(3).atTime(11, 0), 22, "PENDIENTE", "MEDIA"),
                new Reservation("RSV-0012", "SALA-103", "Joaquin Rios", "Comite de Investigacion", base.plusDays(3).atTime(11, 0), base.plusDays(3).atTime(12, 0), 8, "CONFIRMADA", "BAJA"),
                new Reservation("RSV-0013", "SALA-302", "Paula Campos", "Analitica Avanzada", base.plusDays(3).atTime(14, 0), base.plusDays(3).atTime(16, 0), 33, "CONFIRMADA", "ALTA"),
                new Reservation("RSV-0014", "SALA-102", "Bruno Aguilar", "Contenedores y Docker", base.plusDays(4).atTime(8, 0), base.plusDays(4).atTime(10, 0), 31, "OBSERVADA", "ALTA"),
                new Reservation("RSV-0015", "SALA-401", "Daniela Chavez", "Demo Day", base.plusDays(4).atTime(17, 0), base.plusDays(4).atTime(19, 0), 82, "OBSERVADA", "ALTA")
        );
    }

    private static List<Correo> seedCorreos() {
        Faker faker = new Faker(new Locale("es"));
        List<Correo> correos = new ArrayList<>();

        Set<String> nombresUsados = new HashSet<>();
        Set<String> emailsUsados = new HashSet<>();
        Set<String> empresasUsadas = new HashSet<>();

        for (int i = 1; i <= 100; i++) {
            String nombre = uniqueValue(
                    nombresUsados,
                    () -> faker.name().fullName(),
                    "Persona Campus " + i
            );

            String email = uniqueValue(
                    emailsUsados,
                    () -> faker.internet().emailAddress(),
                    "usuario" + i + "@campusspace.test"
            );

            String empresa = uniqueValue(
                    empresasUsadas,
                    () -> faker.company().name(),
                    "Empresa Campus " + i
            );

            correos.add(new Correo(
                    String.format("COR-%04d", i),
                    nombre,
                    email,
                    empresa
            ));
        }

        return correos;
    }

    private static String uniqueValue(Set<String> used, Supplier<String> generator, String fallback) {
        for (int attempt = 0; attempt < 20; attempt++) {
            String value = generator.get();
            if (value != null && !value.isBlank() && used.add(value)) {
                return value;
            }
        }
        used.add(fallback);
        return fallback;
    }

    private static String correosJson(List<Correo> correos) {
        StringBuilder json = new StringBuilder();
        json.append("{\"total\":").append(correos.size()).append(",\"items\":[");
        for (int i = 0; i < correos.size(); i++) {
            if (i > 0) json.append(',');
            Correo correo = correos.get(i);
            json.append("{\"id\":\"").append(escape(correo.id)).append("\",")
                    .append("\"nombre\":\"").append(escape(correo.nombre)).append("\",")
                    .append("\"correo\":\"").append(escape(correo.email)).append("\",")
                    .append("\"empresa\":\"").append(escape(correo.empresa)).append("\"}");
        }
        json.append("]}");
        return json.toString();
    }

    private static String correosHtml(List<Correo> correos) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html lang='es'><head>")
                .append("<meta charset='UTF-8'>")
                .append("<title>CampusSpace - Correos Faker</title>")
                .append("<style>")
                .append("body{font-family:Arial,sans-serif;margin:24px;color:#1f2937;}")
                .append("table{border-collapse:collapse;width:100%;}")
                .append("th,td{border:1px solid #d1d5db;padding:8px;text-align:left;}")
                .append("th{background:#f3f4f6;}")
                .append(".badge{display:inline-block;background:#e0f2fe;padding:6px 10px;border-radius:10px;margin-bottom:12px;}")
                .append("</style>")
                .append("</head><body>")
                .append("<h1>Correos generados con Faker</h1>")
                .append("<p class='badge'>Total mostrado: ").append(correos.size()).append("</p>")
                .append("<table><thead><tr>")
                .append("<th>ID</th><th>Nombre</th><th>Correo</th><th>Empresa</th>")
                .append("</tr></thead><tbody>");

        for (Correo correo : correos) {
            html.append("<tr>")
                    .append("<td>").append(escapeHtml(correo.id)).append("</td>")
                    .append("<td>").append(escapeHtml(correo.nombre)).append("</td>")
                    .append("<td>").append(escapeHtml(correo.email)).append("</td>")
                    .append("<td>").append(escapeHtml(correo.empresa)).append("</td>")
                    .append("</tr>");
        }

        html.append("</tbody></table></body></html>");
        return html.toString();
    }

    private static String readResource(String name) throws IOException {
        try (InputStream inputStream = SourceServer.class.getClassLoader().getResourceAsStream(name)) {
            if (inputStream == null) return "";
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> query = new HashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) return query;
        for (String pair : rawQuery.split("&")) {
            String[] parts = pair.split("=", 2);
            String key = decode(parts[0]);
            String value = parts.length > 1 ? decode(parts[1]) : "";
            query.put(key, value);
        }
        return query;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static int parseInt(String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static int readPort(int fallback) {
        String env = System.getenv("PORT");
        if (env == null || env.isBlank()) env = System.getProperty("PORT");
        return parseInt(env, fallback);
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

    private static class Correo {
        final String id;
        final String nombre;
        final String email;
        final String empresa;

        Correo(String id, String nombre, String email, String empresa) {
            this.id = id;
            this.nombre = nombre;
            this.email = email;
            this.empresa = empresa;
        }
    }

    private static class Room {
        final String id;
        final String sede;
        final String nombre;
        final String tipo;
        final int capacidad;
        final List<String> recursos;

        Room(String id, String sede, String nombre, String tipo, int capacidad, List<String> recursos) {
            this.id = id;
            this.sede = sede;
            this.nombre = nombre;
            this.tipo = tipo;
            this.capacidad = capacidad;
            this.recursos = recursos;
        }
    }

    private static class Reservation {
        final String id;
        final String salaId;
        final String responsable;
        final String curso;
        final LocalDateTime inicio;
        final LocalDateTime fin;
        final int participantes;
        final String estado;
        final String prioridad;

        Reservation(String id, String salaId, String responsable, String curso, LocalDateTime inicio, LocalDateTime fin, int participantes, String estado, String prioridad) {
            this.id = id;
            this.salaId = salaId;
            this.responsable = responsable;
            this.curso = curso;
            this.inicio = inicio;
            this.fin = fin;
            this.participantes = participantes;
            this.estado = estado;
            this.prioridad = prioridad;
        }
    }
}
