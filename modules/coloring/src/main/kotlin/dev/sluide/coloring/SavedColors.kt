package dev.sluide.coloring

import dev.sluide.projectdata.ProjectDataStore
import org.springframework.stereotype.Component

/** The colors saved per project in the project data (ADR 0109). The API follows with the saved-colors section. */
@Component
class SavedColors(
    private val store: ProjectDataStore,
)
