package com.securepass.modules.access.application;

import com.securepass.modules.access.domain.AccessLog;
import com.securepass.modules.access.domain.DenialReason;
import com.securepass.modules.access.domain.GuestEnrollment;

public sealed interface EnrollmentResult {

    AccessLog log();

    record Enrolled(GuestEnrollment enrollment, AccessLog log, String guestName) implements EnrollmentResult {}

    record Denied(DenialReason reason, AccessLog log) implements EnrollmentResult {}
}
