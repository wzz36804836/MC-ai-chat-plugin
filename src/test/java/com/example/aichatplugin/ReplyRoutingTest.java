package com.example.aichatplugin;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import org.mockito.MockedStatic;
import static org.mockito.Mockito.*;

class ReplyRoutingTest {
    @Test void privateReplyOnlyReachesRequester() throws Exception { route(false); }
    @Test void broadcastReplyReachesAllOnlinePlayers() throws Exception { route(true); }

    private void route(boolean broadcast) throws Exception {
        AIChatPlugin plugin = mock(AIChatPlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        ConfigLoader config = mock(ConfigLoader.class);
        when(config.isBroadcastEnabled()).thenReturn(broadcast);
        ConversationManager manager = mock(ConversationManager.class);
        set(manager, "plugin", plugin);
        set(manager, "config", config);
        Player requester = mock(Player.class);
        Player other = mock(Player.class);
        when(requester.isOnline()).thenReturn(true);
        UUID id = UUID.randomUUID();
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(requester);
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(requester, other));
            Class<?> task = Class.forName(ConversationManager.class.getName() + "$ResponseTask");
            Constructor<?> constructor = task.getDeclaredConstructor(ConversationManager.class, UUID.class, String.class);
            constructor.setAccessible(true);
            Object response = constructor.newInstance(manager, id, "hello");
            Method execute = task.getDeclaredMethod("execute");
            execute.setAccessible(true);
            execute.invoke(response);
            verify(requester).sendMessage(anyString());
            if (broadcast) verify(other).sendMessage(anyString());
            else verifyNoInteractions(other);
        }
    }

    private void set(Object object, String name, Object value) throws Exception {
        Field field = ConversationManager.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }
}
