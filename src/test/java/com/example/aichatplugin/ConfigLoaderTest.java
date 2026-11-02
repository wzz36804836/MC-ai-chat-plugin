package com.example.aichatplugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConfigLoaderTest {
    @TempDir Path directory;

    private ConfigLoader load(String yaml) throws Exception {
        Files.writeString(directory.resolve("config.yml"), yaml);
        Files.writeString(directory.resolve("prompts.yml"), "roles: {}\n");
        AIChatPlugin plugin = mock(AIChatPlugin.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        return new ConfigLoader(plugin);
    }

    @Test void invalidStartupPreservesOriginalFile() throws Exception {
        String invalid = "settings: [unterminated";
        assertThrows(IllegalStateException.class, () -> load(invalid));
        assertEquals(invalid, Files.readString(directory.resolve("config.yml")));
    }

    @Test void missingSettingsDoesNotReplaceUserConfiguration() throws Exception {
        String invalid = "other: custom\n";
        assertThrows(IllegalStateException.class, () -> load(invalid));
        assertEquals(invalid, Files.readString(directory.resolve("config.yml")));
    }

    @Test void failedReloadKeepsLastValidSnapshot() throws Exception {
        ConfigLoader config = load("settings:\n  api-key: test-original-key\n  chat-prefix: '!'\n");
        String invalid = "settings: [unterminated";
        Files.writeString(directory.resolve("config.yml"), invalid);
        // The existing reload entrypoint logs failures; either way the last snapshot must survive.
        try { config.reloadConfig(); } catch (IllegalArgumentException ignored) { }
        assertEquals("test-original-key", config.getApiKey());
        assertEquals("!", config.getChatPrefix());
        assertEquals(invalid, Files.readString(directory.resolve("config.yml")));
    }

    @Test void apiKeyUpdateRoundTripsYaml() throws Exception {
        ConfigLoader config = load("settings:\n  api-key: test-original-key\n");
        config.setApiKey("test-updated-key");
        assertEquals("test-updated-key", config.getApiKey());
        assertTrue(Files.readString(directory.resolve("config.yml")).contains("test-updated-key"));
    }
}
