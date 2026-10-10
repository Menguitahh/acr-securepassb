package com.securepass.modules.identity.application;

import java.util.List;
import java.util.UUID;

/**
 * securepassb#121: el admin de otro barrio pide revisar una suspension. El
 * listener manda un email a cada admin del barrio que la puso.
 */
public record SuspensionReviewRequestedEvent(
        UUID userId,
        String personName,
        List<String> adminEmails,
        String requesterName,
        String requesterEmail,
        String requesterNeighborhood,
        String message) {}
