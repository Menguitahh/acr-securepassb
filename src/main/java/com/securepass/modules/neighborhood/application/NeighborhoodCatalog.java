package com.securepass.modules.neighborhood.application;

import java.util.List;

/** securepassb#258: todos los barrios, con sus lotes y admins. */
public interface NeighborhoodCatalog {
    List<NeighborhoodOverview> overview();
}
