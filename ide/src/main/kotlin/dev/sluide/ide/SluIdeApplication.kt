package dev.sluide.ide

import javafx.application.Application
import javafx.stage.Stage
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext

/** Spring configuration; component scanning covers all SluIde modules below `dev.sluide`. */
@SpringBootApplication(scanBasePackages = ["dev.sluide"])
class SluIdeConfiguration

/** JavaFX application that owns the lifecycle of the Spring context. */
class SluIdeApplication : Application() {
    private lateinit var context: ConfigurableApplicationContext

    override fun init() {
        context =
            SpringApplicationBuilder(SluIdeConfiguration::class.java)
                .web(WebApplicationType.NONE)
                .headless(false)
                .run(*parameters.raw.toTypedArray())
    }

    override fun start(primaryStage: Stage) {
        context.getBean(ProjectWindows::class.java).show(primaryStage)
    }

    override fun stop() {
        context.close()
    }
}
