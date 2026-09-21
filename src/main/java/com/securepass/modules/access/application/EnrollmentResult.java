package com.securepass.modules.access.application;

import com.securepass.modules.access.domain.AccessDecision.SubjectKind;
import com.securepass.modules.access.domain.AccessLog;
import com.securepass.modules.access.domain.DenialReason;
import com.securepass.modules.access.domain.GuestEnrollment;

import java.util.UUID;

/**
 * Resultado del ramo de enrolamiento. Discriminado por {@link SubjectKind}
 * (GUEST / WORKER) para que el shape HTTP pueda ser aditivo — un mismo
 * endpoint sirve al invitado y al trabajador (WBS-122), pero cada rama
 * lleva sus propios campos en la respuesta. Consistente con
 * {@code AccessDecision.NeedsEnrollment}, que ya usa el mismo enum.
 */
public sealed interface EnrollmentResult {

    AccessLog log();

    SubjectKind subjectKind();

    record Enrolled(
            SubjectKind subjectKind,
            UUID workerUserId,
            GuestEnrollment enrollment,
            AccessLog log,
            String subjectName
    ) implements EnrollmentResult {
        public Enrolled {
            if (subjectKind == null) throw new IllegalArgumentException("subjectKind requerido");
            if (enrollment == null) throw new IllegalArgumentException("enrollment requerido");
            if (log == null) throw new IllegalArgumentException("log requerido");
            if (subjectName == null || subjectName.isBlank())
                throw new IllegalArgumentException("subjectName requerido");
            if (subjectKind == SubjectKind.GUEST && workerUserId != null) {
                throw new IllegalArgumentException("workerUserId no aplica a GUEST");
            }
            // WORKER: workerUserId puede quedar null en el retry (reconstruct
            // desde AccessLog no lo tiene). En el path online del primer scan
            // siempre es no-null porque viene del payload del token.
        }

        public static Enrolled guest(GuestEnrollment enrollment, AccessLog log, String subjectName) {
            return new Enrolled(SubjectKind.GUEST, null, enrollment, log, subjectName);
        }

        public static Enrolled worker(UUID workerUserId, GuestEnrollment enrollment, AccessLog log, String subjectName) {
            return new Enrolled(SubjectKind.WORKER, workerUserId, enrollment, log, subjectName);
        }

        public static Enrolled workerFromLog(GuestEnrollment enrollment, AccessLog log, String subjectName) {
            return new Enrolled(SubjectKind.WORKER, null, enrollment, log, subjectName);
        }
    }

    record Denied(
            SubjectKind subjectKind,
            UUID workerUserId,
            DenialReason reason,
            AccessLog log
    ) implements EnrollmentResult {
        public Denied {
            if (subjectKind == null) throw new IllegalArgumentException("subjectKind requerido");
            if (reason == null) throw new IllegalArgumentException("reason requerido");
            if (log == null) throw new IllegalArgumentException("log requerido");
            if (subjectKind == SubjectKind.GUEST && workerUserId != null) {
                throw new IllegalArgumentException("workerUserId no aplica a GUEST");
            }
            // WORKER: workerUserId puede ser null en el retry (reconstruct).
        }

        public static Denied guest(DenialReason reason, AccessLog log) {
            return new Denied(SubjectKind.GUEST, null, reason, log);
        }

        public static Denied worker(UUID workerUserId, DenialReason reason, AccessLog log) {
            return new Denied(SubjectKind.WORKER, workerUserId, reason, log);
        }
    }
}
