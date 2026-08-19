package com.securepass.modules.identity.application;

import java.util.UUID;

public interface UserProfileQuery {
    UserProfileSummary summarize(UUID userId);
}
