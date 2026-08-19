package com.securepass.modules.identity.application;

import com.securepass.modules.identity.domain.User;

import java.util.Optional;

public interface UserRepository {
    Optional<User> findByIdentifier(String identifier);
}
