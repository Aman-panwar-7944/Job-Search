package app;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class StaticHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange ex) throws IOException {
        String p = ex.getRequestURI().getPath();
        if (p.equals("/")) p = "/index.html";
        if (p.contains("..")) { ex.sendResponseHeaders(400, -1); ex.close(); return; }
        try (InputStream in = StaticHandler.class.getResourceAsStream("/static" + p)) {
            if (in == null) {
                byte[] nf = "Not found".getBytes();
                ex.sendResponseHeaders(404, nf.length);
                try (OutputStream o = ex.getResponseBody()) { o.write(nf); }
                return;
            }
            byte[] b = in.readAllBytes();
            String type = p.endsWith(".html") ? "text/html; charset=utf-8"
                    : p.endsWith(".css") ? "text/css; charset=utf-8"
                    : p.endsWith(".js") ? "application/javascript; charset=utf-8"
                    : "application/octet-stream";
            ex.getResponseHeaders().set("Content-Type", type);
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream o = ex.getResponseBody()) { o.write(b); }
        }
    }
}
