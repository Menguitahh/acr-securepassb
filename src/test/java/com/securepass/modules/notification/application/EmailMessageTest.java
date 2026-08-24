package com.securepass.modules.notification.application;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EmailMessageTest {

    @Test
    void aceptaMensajeCompleto() {
        EmailSender.EmailMessage m = new EmailSender.EmailMessage(
                "alguien@dev.local", "Activá tu cuenta", "<p>hi</p>", "hi");
        assertEquals("alguien@dev.local", m.to());
    }

    @Test
    void rechazaDestinatarioSinArroba() {
        assertThrows(IllegalArgumentException.class,
                () -> new EmailSender.EmailMessage("no-es-email", "x", "<p/>", "x"));
    }

    @Test
    void rechazaDestinatarioVacio() {
        assertThrows(IllegalArgumentException.class,
                () -> new EmailSender.EmailMessage("  ", "x", "<p/>", "x"));
    }

    @Test
    void rechazaAsuntoVacio() {
        assertThrows(IllegalArgumentException.class,
                () -> new EmailSender.EmailMessage("a@b.c", " ", "<p/>", "x"));
    }

    @Test
    void rechazaCuerposVacios() {
        assertThrows(IllegalArgumentException.class,
                () -> new EmailSender.EmailMessage("a@b.c", "x", "", ""));
    }

    @Test
    void aceptaSoloTexto() {
        EmailSender.EmailMessage m = new EmailSender.EmailMessage("a@b.c", "x", null, "hola");
        assertNull(m.htmlBody());
        assertEquals("hola", m.textBody());
    }

    @Test
    void aceptaSoloHtml() {
        EmailSender.EmailMessage m = new EmailSender.EmailMessage("a@b.c", "x", "<p>hi</p>", null);
        assertNull(m.textBody());
    }
}
