package com.example.aichatplugin.web;

import com.example.aichatplugin.AIChatPlugin;
import com.example.aichatplugin.ConfigLoader;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebServerTest {
    @Test void realHttpLoginAndProtectedRoutes() throws Exception {
        AIChatPlugin plugin = mock(AIChatPlugin.class);
        ConfigLoader config = mock(ConfigLoader.class);
        when(plugin.getConfigLoader()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(config.getString("web.host", "127.0.0.1")).thenReturn("127.0.0.1");
        // Port zero allocates an isolated ephemeral port for the test.
        when(config.getInt("web.port", 28080)).thenReturn(0);
        WebServer server = new WebServer(plugin);
        when(plugin.getWebServer()).thenReturn(server);
        try {
            assertTrue(server.start());
            String base = "http://127.0.0.1:" + server.getPort();
            try (HttpClient client = HttpClient.newHttpClient()) {
                assertEquals(200, send(client, base, "/", null, null).statusCode());
                assertEquals(401, send(client, base, "/api/status", null, null).statusCode());
                assertEquals(401, send(client, base, "/api/config", null, null).statusCode());
                assertEquals(401, send(client, base, "/api/auth/login", "{\"token\":\"wrong\"}", null).statusCode());
                // Previously any body containing the token was accepted, regardless of JSON fields.
                assertEquals(401, send(client, base, "/api/auth/login", "{\"token\":\"wrong\",\"extra\":\"" + server.getAccessToken() + "\"}", null).statusCode());
                assertEquals(200, send(client, base, "/api/auth/login", "{\"token\":\"" + server.getAccessToken() + "\"}", null).statusCode());
                assertEquals(400, send(client, base, "/api/auth/login", "not-json", null).statusCode());
                assertEquals(413, send(client, base, "/api/auth/login", "x".repeat(4097), null).statusCode());
                assertEquals(200, send(client, base, "/api/auth/verify", "{}", server.getAccessToken()).statusCode());
            }
        } finally {
            server.stop();
        }
        assertFalse(server.isRunning());
    }

    private HttpResponse<String> send(HttpClient client, String base, String path, String body, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(5));
        if (body != null) builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
