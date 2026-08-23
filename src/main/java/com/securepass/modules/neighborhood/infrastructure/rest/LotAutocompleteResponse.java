package com.securepass.modules.neighborhood.infrastructure.rest;

import com.securepass.modules.neighborhood.domain.Lot;

import java.util.UUID;

public record LotAutocompleteResponse(UUID id, String code) {
    public static LotAutocompleteResponse from(Lot lot) {
        return new LotAutocompleteResponse(lot.id(), lot.code());
    }
}
