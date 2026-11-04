package chatbot.server;

import chatbot.brain.ChatBot;
import chatbot.util.Json;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Embedded HTTP server (JDK {@code com.sun.net.httpserver}) that exposes the
 * chatbot over a small JSON API and serves the browser chat UI.
 *
 * <p>Endpoints:</p>
 * <ul>
 *   <li>{@code POST /api/chat}        - {message} -&gt; {reply, intent, confidence, method}</li>
 *   <li>{@code POST /api/train}       - {question, answer, intent} -&gt; teach the bot</li>
 *   <li>{@code GET  /api/stats}       - corpus / model statistics</li>
 *   <li>{@code GET  /api/suggestions} - starter questions for the UI</li>
 *   <li>{@code GET  /}                - the static web interface</li>
 * </ul>
 */
public final class ChatServer {

    private final HttpServer server;
    private final Path webRoot;
    private final ChatBot bot;

    public ChatServer(int port, Path webRoot, ChatBot bot) throws IOException {
        this.webRoot = webRoot;
        this.bot = bot;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.server.setExecutor(Executors.newFixedThreadPool(8));
        server.createContext("/api/chat", this::handleChat);
        server.createContext("/api/train", this::handleTrain);
        server.createContext("/api/stats", this::handleStats);
        server.createContext("/api/suggestions", this::handleSuggestions);
        server.createContext("/", this::handleStatic);
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    // -------------------------------------------------------------- handlers

    private void handleChat(HttpExchange exchange) throws IOException {
        if (preflight(exchange)) {
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, error("use POST for /api/chat"));
            return;
        }
        Map<String, Object> body = Json.parseObject(readBody(exchange));
        String message = String.valueOf(body.getOrDefault("message", ""));
        ChatBot.Reply reply = bot.respond(message);
        send(exchange, 200, Json.object(toJson(reply)));
    }

    private void handleTrain(HttpExchange exchange) throws IOException {
        if (preflight(exchange)) {
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, error("use POST for /api/train"));
            return;
        }
        Map<String, Object> body = Json.parseObject(readBody(exchange));
        String question = String.valueOf(body.getOrDefault("question", ""));
        String answer = String.valueOf(body.getOrDefault("answer", ""));
        String intent = String.valueOf(body.getOrDefault("intent", ""));
        ChatBot.Reply reply = bot.teach(question, answer, intent);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", reply.method.equals("learning"));
        payload.put("faqs", bot.faqCount());
        payload.put("reply", toJson(reply));
        send(exchange, 200, Json.object(payload));
    }

    private void handleStats(HttpExchange exchange) throws IOException {
        if (preflight(exchange)) {
            return;
        }
        send(exchange, 200, Json.object(bot.stats()));
    }

    private void handleSuggestions(HttpExchange exchange) throws IOException {
        if (preflight(exchange)) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("suggestions", bot.sampleQuestions(6));
        payload.put("intents", bot.intents());
        send(exchange, 200, Json.object(payload));
    }

    // --------------------------------------------------------------- static

    private void handleStatic(HttpExchange exchange) throws IOException {
        if (preflight(exchange)) {
            return;
        }
        String rawPath = exchange.getRequestURI().getPath();
        if (rawPath == null || rawPath.isEmpty() || "/".equals(rawPath)) {
            rawPath = "/index.html";
        }
        Path resolved;
        try {
            resolved = webRoot.resolve(rawPath.substring(1)).normalize();
        } catch (RuntimeException e) {
            send(exchange, 404, error("not found"));
            return;
        }
        if (!resolved.startsWith(webRoot)) {
            send(exchange, 403, error("forbidden"));
            return;
        }
        if (!Files.isRegularFile(resolved)) {
            send(exchange, 404, error("not found: " + rawPath));
            return;
        }
        byte[] bytes = Files.readAllBytes(resolved);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType(resolved));
        headers.set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String contentType(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".html") || name.endsWith(".htm")) return "text/html; charset=utf-8";
        if (name.endsWith(".css")) return "text/css; charset=utf-8";
        if (name.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (name.endsWith(".json")) return "application/json; charset=utf-8";
        if (name.endsWith(".svg")) return "image/svg+xml";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".ico")) return "image/x-icon";
        if (name.endsWith(".txt") || name.endsWith(".md")) return "text/plain; charset=utf-8";
        return "application/octet-stream";
    }

    // --------------------------------------------------------------- plumbing

    private boolean preflight(HttpExchange exchange) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Access-Control-Allow-Origin", "*");
        headers.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        headers.set("Access-Control-Allow-Headers", "Content-Type");
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return true;
        }
        return false;
    }

    private void send(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String error(String message) {
        return Json.object(Map.of("error", message));
    }

    private static Map<String, Object> toJson(ChatBot.Reply reply) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("reply", reply.text);
        map.put("intent", reply.intent);
        map.put("confidence", reply.confidence);
        map.put("method", reply.method);
        List<String> suggestions = new ArrayList<>(reply.suggestions);
        map.put("suggestions", suggestions);
        return map;
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
