package com.securepass.modules.access.infrastructure.rest;

import com.securepass.modules.access.application.EnrollmentResult;

import java.time.Instant;
import java.util.UUID;

public record EnrollResponse(
        String result,
        String reason,
        Instant enrolledAt,
        UUID enrollmentId,
        Guest guest
) {
    public record Guest(String name, String document) {}

    public static EnrollResponse from(EnrollmentResult r) {
        if (r instanceof EnrollmentResult.Enrolled ok) {
            return new EnrollResponse(
                    "ALLOWED", null, ok.enrollment().enrolledAt(), ok.enrollment().id(),
                    new Guest(ok.guestName(), ok.enrollment().documentNumber()));
        }
        EnrollmentResult.Denied denied = (EnrollmentResult.Denied) r;
        return new EnrollResponse(
                "DENIED", denied.reason().name(), null, null,
                new Guest(null, denied.log().subjectDocument()));
    }
}
