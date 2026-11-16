package dev.sluide.java.settings

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.java.JavaSettings
import dev.sluide.java.JavaWorkspaceUse
import dev.sluide.java.JavaWorkspaces
import dev.sluide.java.JdtlsInstaller
import dev.sluide.java.JdtlsUpdates
import dev.sluide.servertools.ServerToolDialogs
import dev.sluide.servertools.ServerToolPageTexts
import dev.sluide.servertools.ServerToolSettingsPage
import javafx.application.Platform
import javafx.beans.property.ReadOnlyStringWrapper
import javafx.collections.SetChangeListener
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.util.StringConverter
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.util.concurrent.Executor

private val TEXTS =
    ServerToolPageTexts(
        language = "Java",
        heading = "jdtls (Eclipse JDT Language Server), the language server for Java",
        note =
            "Thoreau downloads jdtls from download.eclipse.org and Lombok from repo1.maven.org only on Install or " +
                "Update, and checks for updates only when asked. A custom jdtls is the bin/jdtls file of an unpacked " +
                "jdtls. Importing a Maven or Gradle build runs its build (plugins, scripts and the downloads of its " +
                "dependencies) after the folder is trusted.",
    )

/** Languages → Java: jdtls, its maximum memory and its workspace folders (ADR 0179). */
@Component
class JavaSettingsPage internal constructor(
    settings: JavaSettings,
    private val installer: JdtlsInstaller,
    updates: JdtlsUpdates,
    workspaces: JavaWorkspaces,
    private val use: JavaWorkspaceUse,
    questions: ServerToolDialogs?,
    io: Executor = Executor { NamedThreads.virtual("java-workspaces", it) },
    dispatch: (Runnable) -> Unit = Platform::runLater,
) : ServerToolSettingsPage(listOf("Languages", "Java"), TEXTS, settings, installer, updates, questions) {
    @Autowired
    constructor(
        settings: JavaSettings,
        installer: JdtlsInstaller,
        updates: JdtlsUpdates,
        use: JavaWorkspaceUse,
    ) : this(settings, installer, updates, JavaWorkspaces(), use, null)

    internal val extraModel =
        JavaSettingsPageModel(
            settings,
            workspaces,
            use,
            { dialogs.confirmDelete("Delete Java Workspace", it) },
            io,
            dispatch,
        )

    /** The extra controls, created with the node on the FX thread; for tests. */
    internal class Extra(
        val memory: ComboBox<Int>,
        val table: TableView<WorkspaceRow>,
        val deleteButton: Button,
        val message: Label,
    )

    internal var extra: Extra? = null
        private set

    override fun extraContent(): Node {
        val memory =
            ComboBox<Int>().apply {
                id = "java-memory"
                converter =
                    object : StringConverter<Int>() {
                        override fun toString(value: Int?) = value?.let { JavaSettingsPageModel.memoryLabel(it) } ?: ""

                        override fun fromString(text: String?): Int? = null
                    }
                valueProperty().subscribe { value -> if (value != null) extraModel.memory.set(value) }
            }
        val table = workspaceTable()
        val delete = Button("Delete…").apply { id = "java-workspace-delete" }
        val message = Label().apply { isWrapText = true }
        extra = Extra(memory, table, delete, message)
        delete.setOnAction { table.selectionModel.selectedItem?.let(extraModel::delete) }
        message.textProperty().bind(extraModel.message)
        val updateDelete = { updateDelete() }
        table.selectionModel.selectedItemProperty().subscribe(updateDelete)
        use.inUse.addListener(SetChangeListener { updateDelete() })
        showMemory()
        updateDelete()
        return VBox(
            10.0,
            Label("Maximum memory").apply { styleClass += "label-heading" },
            HBox(8.0, memory),
            caption("The most memory jdtls may use. Changing it restarts the Java language server."),
            Label("Java workspaces").apply { styleClass += "label-heading" },
            caption(
                "jdtls keeps an index and its import cache in a folder per project. Deleting one is safe: " +
                    "jdtls builds it again the next time the project is opened.",
            ),
            table,
            HBox(8.0, delete, message),
        )
    }

    override fun loadExtra() {
        extraModel.load()
        showMemory()
    }

    override fun applyExtra() = extraModel.apply()

    override fun applyAll(stores: () -> Unit) = installer.batchCommandChanges(stores)

    private fun showMemory() {
        val memory = extra?.memory ?: return
        memory.items.setAll(extraModel.memoryChoices())
        memory.value = extraModel.memory.get()
    }

    private fun updateDelete() {
        val widgets = extra ?: return
        val button = extraModel.deleteButton(widgets.table.selectionModel.selectedItem)
        widgets.deleteButton.text = button.text
        widgets.deleteButton.isDisable = button.disabled
    }

    private fun workspaceTable(): TableView<WorkspaceRow> =
        TableView(extraModel.rows).apply {
            id = "java-workspaces"
            columnResizePolicy = TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN
            prefHeight = 180.0
            VBox.setVgrow(this, Priority.NEVER)
            columns +=
                listOf(
                    column("Project", 3.0) { it.project },
                    column("Size", 1.0) { it.size },
                    column("Last used", 1.5) { it.lastUsed },
                )
            placeholderProperty().value = Label().apply { textProperty().bind(placeholderText()) }
        }

    private fun placeholderText() =
        extraModel.loading.map { loading -> if (loading) "Looking for Java workspaces…" else "No Java workspaces yet." }

    private fun column(
        title: String,
        weight: Double,
        text: (WorkspaceRow) -> String,
    ) = TableColumn<WorkspaceRow, String>(title).apply {
        setCellValueFactory { ReadOnlyStringWrapper(text(it.value)) }
        prefWidth = 100.0 * weight
        isSortable = false
    }

    private fun caption(text: String) =
        Label(text).apply {
            styleClass += "preview-caption"
            isWrapText = true
        }
}
