package com.securepass.modules.notification.application;

/**
 * Puerto de emails transaccionales (WBS-100, US-50). Los casos de uso que
 * disparen envio deben hacerlo con {@code @Async("emailExecutor")} para no
 * bloquear el hilo del request: un email lento no puede colgar la operacion
 * que lo dispara, y un fallo de envio no la rompe.
 */
public interface EmailSender {

    void send(EmailMessage message);

    record EmailMessage(String to, String subject, String htmlBody, String textBody) {
        public EmailMessage {
            if (to == null || to.isBlank()) throw new IllegalArgumentException("to requerido");
            if (!to.contains("@")) throw new IllegalArgumentException("to no parece un email");
            if (subject == null || subject.isBlank()) throw new IllegalArgumentException("subject requerido");
            boolean sinHtml = htmlBody == null || htmlBody.isBlank();
            boolean sinTexto = textBody == null || textBody.isBlank();
            if (sinHtml && sinTexto) throw new IllegalArgumentException("al menos htmlBody o textBody");
        }
    }
}
