package com.example.webapp;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * Simple servlet that responds to GET requests with a greeting.
 * Deployed at /hello inside the webapp context.
 */
public class HelloServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String name = req.getParameter("name");
        if (name == null || name.isBlank()) {
            name = "World";
        }
        resp.setContentType("text/html;charset=UTF-8");
        try (PrintWriter out = resp.getWriter()) {
            out.println("<html><body>");
            out.println("<h1>Hello, " + escapeHtml(name) + "!</h1>");
            out.println("<p>CI/CD pipeline is working.</p>");
            out.println("</body></html>");
        }
    }

    /** Minimal HTML escaping to prevent XSS. */
    static String escapeHtml(String input) {
        return input
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#x27;");
    }
}
