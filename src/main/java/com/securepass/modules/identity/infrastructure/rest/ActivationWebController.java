package com.securepass.modules.identity.infrastructure.rest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * WBS-102: fallback web para {@code GET /activar/{token}} cuando el
 * enlace se abre desde un browser en vez de disparar el deep link de la
 * app. Una sola pagina HTML sin framework: pide instalar la app y muestra
 * el link para copiar. No es el dashboard.
 */
@Controller
@RequestMapping("/activar")
public class ActivationWebController {

    private final String appDeepLinkScheme;

    public ActivationWebController(
            @Value("${securepass.activation.app-scheme:securepass}") String appDeepLinkScheme) {
        this.appDeepLinkScheme = appDeepLinkScheme;
    }

    @GetMapping(value = "/{token}", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    @PreAuthorize("permitAll()")
    public String page(@PathVariable String token) {
        String safeToken = escapeHtml(token);
        String deepLink = appDeepLinkScheme + "://activar/" + safeToken;
        return """
                <!doctype html>
                <html lang="es">
                  <head>
                    <meta charset="utf-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    <meta name="robots" content="noindex, nofollow">
                    <title>Activar cuenta - Securepass</title>
                    <style>
                      body { font-family: system-ui, sans-serif; max-width: 480px; margin: 3rem auto; padding: 0 1rem; color: #1f2937; }
                      h1 { font-size: 1.25rem; }
                      .box { background: #f3f4f6; padding: 1rem; border-radius: 8px; word-break: break-all; font-family: ui-monospace, monospace; font-size: 0.9rem; }
                      a.btn { display: inline-block; margin-top: 1rem; padding: 0.75rem 1rem; background: #2563eb; color: white; text-decoration: none; border-radius: 6px; }
                    </style>
                  </head>
                  <body>
                    <h1>Activá tu cuenta desde el celular</h1>
                    <p>Abrí este enlace desde tu teléfono con Securepass instalado. Si no tenés la app todavía, descargala e ingresá con este link:</p>
                    <div class="box">%DEEPLINK%</div>
                    <a class="btn" href="%DEEPLINK%">Abrir en la app</a>
                  </body>
                </html>
                """.replace("%DEEPLINK%", deepLink);
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
