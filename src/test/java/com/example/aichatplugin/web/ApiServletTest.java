package com.example.aichatplugin.web;

import com.example.aichatplugin.AIChatPlugin;
import com.example.aichatplugin.web.controllers.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.logging.Logger;
import static org.mockito.Mockito.*;

class ApiServletTest {
    @Test void statusRequiresAuthentication() throws Exception { check("/status", null, false); }
    @Test void wrongTokenCannotReadConfig() throws Exception { check("/config", "Bearer wrong", false); }
    @Test void validTokenCanReadStatus() throws Exception { check("/status", "Bearer accepted", true); }

    private void check(String path, String header, boolean accepted) throws Exception {
        AIChatPlugin plugin = mock(AIChatPlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        WebServer web = mock(WebServer.class);
        when(plugin.getWebServer()).thenReturn(web);
        when(web.isTokenRequired()).thenReturn(true);
        when(web.validateToken("accepted")).thenReturn(true);
        StatusController status = mock(StatusController.class);
        ConfigController config = mock(ConfigController.class);
        ApiServlet servlet = new ApiServlet(plugin, config, status, mock(BenchmarkController.class));
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getPathInfo()).thenReturn(path);
        when(request.getHeader("Authorization")).thenReturn(header);
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        servlet.doGet(request, response);
        if (accepted) {
            verify(status).doGet(request, response);
            verify(response, never()).setStatus(401);
        } else {
            verify(response).setStatus(401);
            verifyNoInteractions(status, config);
        }
    }
}
