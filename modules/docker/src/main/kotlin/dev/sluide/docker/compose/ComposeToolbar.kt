package dev.sluide.docker.compose

import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.CheckMenuItem
import javafx.scene.control.Label
import javafx.scene.control.MenuButton
import javafx.scene.control.MenuItem
import javafx.scene.control.Separator
import javafx.scene.control.SplitMenuButton
import javafx.scene.control.TextField
import javafx.scene.control.Tooltip
import javafx.scene.layout.HBox
import javafx.scene.layout.Region
import javafx.scene.layout.VBox

/**
 * The top of the Compose pane (ADR 0119): Up with Detached, Down (with Down and Remove Volumes…), Build, Pull and
 * Restart, the note why they are disabled, and the options: profiles, the Up flags and the project name. Disabled
 * until [bind] connects it to a model. FX thread.
 */
internal class ComposeToolbar {
    val up = button("Up", "compose-up")

    val detached = CheckBox("Detached").apply { styleClass += "compose-detached" }

    private val downItem = MenuItem("Down")
    val downVolumes = MenuItem("Down and Remove Volumes…")

    val down =
        SplitMenuButton(downItem, downVolumes).apply {
            text = "Down"
            styleClass += "compose-down"
            minWidth = Region.USE_PREF_SIZE
            isDisable = true
        }

    val build = button("Build", "compose-build")
    val pull = button("Pull", "compose-pull")
    val restart = button("Restart", "compose-restart")

    /** Why the commands are disabled; hidden while they are not. */
    val note =
        Label(NOT_IN_WINDOW).apply {
            styleClass += "compose-note"
            isWrapText = true
        }

    val profiles =
        MenuButton("No profiles").apply {
            styleClass += "compose-profiles"
            minWidth = Region.USE_PREF_SIZE
            isDisable = true
        }

    val buildFlag = flag("--build", "Build images before starting containers (Up)")
    val forceRecreate = flag("--force-recreate", "Recreate containers even if nothing changed (Up)")
    val removeOrphans = flag("--remove-orphans", "Remove containers of services no longer in the file (Up)")

    val projectName =
        TextField().apply {
            styleClass += "compose-project-name"
            prefColumnCount = 12
            tooltip = Tooltip("Project name (-p); empty: compose's own")
        }

    private val commands = listOf(up, down, build, pull, restart)

    val node =
        VBox(
            HBox(up, detached, Separator(javafx.geometry.Orientation.VERTICAL), down, build, pull, restart).apply {
                styleClass += "compose-toolbar"
                alignment = Pos.CENTER_LEFT
            },
            note,
            HBox(profiles, buildFlag, forceRecreate, removeOrphans, Label("Project:"), projectName).apply {
                styleClass += "compose-options"
                alignment = Pos.CENTER_LEFT
            },
        ).apply { styleClass += "compose-top" }

    /** The project name compose picks by itself, as last seen while no name was set. */
    private var defaultName: String? = null

    /** Connects the controls to [model]: commands, enablement, options both ways and the profiles of the file. */
    fun bind(model: ComposePaneModel) {
        up.setOnAction { model.up() }
        down.setOnAction { model.down(removeVolumes = false) }
        downItem.setOnAction { model.down(removeVolumes = false) }
        downVolumes.setOnAction { model.down(removeVolumes = true) }
        build.setOnAction { model.build() }
        pull.setOnAction { model.pull() }
        restart.setOnAction { model.restart() }
        model.enabled.subscribe { _ -> showEnabled(model) }
        model.engine.subscribe { _ -> showEnabled(model) }
        model.waitingUp.subscribe { _ -> showEnabled(model) }
        model.options.subscribe { options -> showOptions(model, options) }
        model.config.subscribe { config ->
            if (config is ConfigLoad.Loaded && model.options.get().projectName == null) defaultName = config.config.name
            projectName.promptText = defaultName ?: ""
            showProfiles(model)
        }
        detached.selectedProperty().subscribe { value -> model.update { it.copy(detached = value) } }
        buildFlag.selectedProperty().subscribe { value -> model.update { it.copy(build = value) } }
        forceRecreate.selectedProperty().subscribe { value -> model.update { it.copy(forceRecreate = value) } }
        removeOrphans.selectedProperty().subscribe { value -> model.update { it.copy(removeOrphans = value) } }
        projectName.textProperty().subscribe { text ->
            val input = projectNameInput(text)
            projectName.styleClass.remove(INVALID)
            when (input) {
                is ProjectNameInput.Valid -> model.update { it.copy(projectName = input.name) }
                ProjectNameInput.Invalid -> projectName.styleClass += INVALID
            }
        }
    }

    private fun showEnabled(model: ComposePaneModel) {
        val enabled = model.enabled.get()
        commands.forEach { it.isDisable = !enabled }
        val text = toolbarNote(model.engine.get(), model.waitingUp.get())
        note.text = text ?: ""
        note.isVisible = text != null
        note.isManaged = text != null
    }

    private fun showOptions(
        model: ComposePaneModel,
        options: ComposeOptions,
    ) {
        detached.isSelected = options.detached
        buildFlag.isSelected = options.build
        forceRecreate.isSelected = options.forceRecreate
        removeOrphans.isSelected = options.removeOrphans
        if (projectNameInput(projectName.text) != ProjectNameInput.Valid(options.projectName)) {
            projectName.text = options.projectName ?: ""
        }
        showProfiles(model)
    }

    private fun showProfiles(model: ComposePaneModel) {
        val chosen = model.options.get().profiles
        val available = (model.config.get() as? ConfigLoad.Loaded)?.config?.profiles.orEmpty()
        profiles.text = profilesLabel(chosen, available)
        profiles.isDisable = (available + chosen).isEmpty()
        profiles.items.setAll(
            (available + chosen).sorted().map { name ->
                CheckMenuItem(name).apply {
                    isSelected = name in chosen
                    setOnAction {
                        val on = isSelected
                        model.update { it.copy(profiles = if (on) it.profiles + name else it.profiles - name) }
                    }
                }
            },
        )
    }

    private fun button(
        text: String,
        styleClass: String,
    ) = Button(text).apply {
        this.styleClass += styleClass
        minWidth = Region.USE_PREF_SIZE
        isDisable = true
    }

    private fun flag(
        text: String,
        tooltip: String,
    ) = CheckBox(text).apply {
        styleClass += "compose-flag"
        this.tooltip = Tooltip(tooltip)
    }

    private companion object {
        const val NOT_IN_WINDOW = "Compose commands run in an IDE window."
        const val INVALID = "compose-invalid"
    }
}
