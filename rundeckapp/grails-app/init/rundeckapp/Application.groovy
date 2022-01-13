package rundeckapp

import grails.boot.GrailsApp
import grails.boot.config.GrailsAutoConfiguration
import org.rundeck.app.bootstrap.PreBootstrap
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.EnvironmentAware
import org.springframework.core.env.Environment
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.PropertiesPropertySource
import rundeckapp.init.ReloadableRundeckPropertySource
import rundeckapp.init.RundeckInitConfig
import rundeckapp.init.RundeckInitializer
import rundeckapp.init.RundeckDbMigration
import rundeckapp.init.prebootstrap.InitializeRundeckPreboostrap

import java.nio.file.Files
import java.nio.file.Paths

@EnableAutoConfiguration(exclude = [SecurityFilterAutoConfiguration])
class Application extends GrailsAutoConfiguration implements EnvironmentAware {
    static final String SYS_PROP_RUNDECK_CONFIG_INITTED = "rundeck.config.initted"
    static RundeckInitConfig rundeckConfig = null
    static ConfigurableApplicationContext ctx;
    static String[] startArgs = []
    static void main(String[] args) {
        Application.startArgs = args
        runPrebootstrap()
        ctx = GrailsApp.run(Application, args)
        if(rundeckConfig.isMigrate()) {
            println "\nMigrations complete"
            System.exit(0)
        }
    }

    static void restartServer() {
        ApplicationArguments args = ctx.getBean(ApplicationArguments.class);
        Thread thread = new Thread({
            ctx.getBean("quartzScheduler").shutdown(true)
            ctx.close()
            ctx = GrailsApp.run(Application, args.getSourceArgs())
        })
        thread.setDaemon(false)
        thread.start()
    }

    @Override
    void setEnvironment(final Environment environment) {
        Properties hardCodedRundeckConfigs = new Properties()
        if(rundeckConfig == null) Application.runPrebootstrap()

        hardCodedRundeckConfigs.setProperty("rundeck.useJaas", rundeckConfig.useJaas.toString())
        hardCodedRundeckConfigs.setProperty(
                "rundeck.security.fileUserDataSource",
                rundeckConfig.runtimeConfiguration.getProperty(RundeckInitializer.PROP_REALM_LOCATION)
        )
        hardCodedRundeckConfigs.setProperty(
                "rundeck.security.jaasLoginModuleName",
                rundeckConfig.runtimeConfiguration.getProperty(RundeckInitializer.PROP_LOGINMODULE_NAME)
        )
        environment.propertySources.addFirst(
                new PropertiesPropertySource("hardcoded-rundeck-props", hardCodedRundeckConfigs)
        )
        environment.propertySources.addFirst(ReloadableRundeckPropertySource.getRundeckPropertySourceInstance())
        if(rundeckConfig.migrate) {
            environment.propertySources.addFirst(new MapPropertySource("ensure-migration-flag",["grails.plugin.databasemigration.updateOnStart":true]))
        }
        loadGroovyRundeckConfigIfExists(environment)
    }

    @Override
    void doWithApplicationContext() {
        if(rundeckConfig.isRollback()) {
            RundeckDbMigration rundeckDbMigration = new RundeckDbMigration(applicationContext)
            println "Beginning db rollback to ${rundeckConfig.tagName()}"
            rundeckDbMigration.rollback(rundeckConfig.tagName())
            println "Rollback complete"
            System.exit(0)
        }
    }

    void doWithDynamicMethods() {
    }
    static void runPrebootstrap() {
        ServiceLoader<PreBootstrap> preBootstraps = ServiceLoader.load(PreBootstrap)
        List<PreBootstrap> preboostraplist = []
        preBootstraps.each { pbs -> preboostraplist.add(pbs) }
        preboostraplist.sort { a,b -> a.order <=> b.order }
        preboostraplist.each { pbs ->
            try {
                pbs.run()
            } catch(Exception ex) {
                System.err.println("PreBootstrap process "+pbs.class.canonicalName+" failed")
                ex.printStackTrace()
            }
        }
    }


    void loadGroovyRundeckConfigIfExists(final Environment environment) {
        String rundeckGroovyConfigFile = System.getProperty(RundeckInitConfig.SYS_PROP_RUNDECK_SERVER_CONFIG_DIR) +
                "/rundeck-config.groovy"

        if (System.getProperty(RundeckInitConfig.SYS_PROP_RUNDECK_CONFIG_LOCATION) && System.getProperty(RundeckInitConfig.SYS_PROP_RUNDECK_CONFIG_LOCATION).endsWith(".groovy")) {
            // if SYS_PROP_RUNDECK_CONFIG_LOCATION is set, get .groovy file from there
            rundeckGroovyConfigFile = System.getProperty(RundeckInitConfig.SYS_PROP_RUNDECK_CONFIG_LOCATION)
        }

        if (Files.exists(Paths.get(rundeckGroovyConfigFile))) {
            def config = new ConfigSlurper().parse(new File(rundeckGroovyConfigFile).toURL())
            environment.propertySources.addFirst(new MapPropertySource("rundeck-config-groovy", config))
        }

    }
}
