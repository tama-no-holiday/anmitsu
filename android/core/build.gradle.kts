plugins { kotlin("jvm") }
kotlin { jvmToolchain(17) }
dependencies { testImplementation("junit:junit:4.13.2") }
tasks.test { testLogging { events("failed", "skipped") } }
tasks.register<JavaExec>("feedbackBenchmark") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.astra.ime.core.FeedbackBenchmark")
}
