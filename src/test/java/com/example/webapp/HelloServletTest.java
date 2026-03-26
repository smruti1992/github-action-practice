package com.example.webapp;

import org.junit.Test;
import static org.junit.Assert.*;

public class HelloServletTest {

    @Test
    public void escapeHtml_sanitisesSpecialCharacters() {
        assertEquals("&lt;script&gt;", HelloServlet.escapeHtml("<script>"));
        assertEquals("O&#x27;Brien", HelloServlet.escapeHtml("O'Brien"));
        assertEquals("a&amp;b", HelloServlet.escapeHtml("a&b"));
        assertEquals("&quot;quoted&quot;", HelloServlet.escapeHtml("\"quoted\""));
    }

    @Test
    public void escapeHtml_plainTextUnchanged() {
        assertEquals("Hello World", HelloServlet.escapeHtml("Hello World"));
    }
}
