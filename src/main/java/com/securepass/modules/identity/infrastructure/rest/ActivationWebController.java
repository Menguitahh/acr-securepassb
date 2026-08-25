package com.securepass.modules.identity.infrastructure.rest;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * WBS-104/105: pagina web server-rendered para activacion y reset de
 * contrasena. Un solo template; el JS lee purpose desde el GET y elige
 * texto y endpoint. No hay app movil que intercepte el enlace en v1: esta
 * es la primera pantalla del sistema para el residente recien dado de alta.
 */
@Controller
@RequestMapping("/activar")
public class ActivationWebController {

    private final String html;

    public ActivationWebController() {
        this.html = load("templates/activar.html");
    }

    @GetMapping(value = "/{token}", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    @PreAuthorize("permitAll()")
    public ResponseEntity<String> page(@PathVariable String token) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("Referrer-Policy", "no-referrer")
                .contentType(MediaType.TEXT_HTML)
                .body(html);
    }

    private static String load(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("no pude cargar " + path, e);
        }
    }
}
