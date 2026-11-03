plugins { kotlin("jvm"); kotlin("plugin.serialization") }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    testImplementation(kotlin("test"))
}
tasks.test {
    useJUnitPlatform()
    systemProperty("cpu.arena.baselineJar", providers.gradleProperty("cpuArenaBaselineJar").getOrElse(""))
}

tasks.register<Jar>("cpuBaselineJar") {
    dependsOn(tasks.testClasses)
    from(sourceSets.main.get().output)
    from(sourceSets.test.get().output) { include("com/tcgtrainer/engine/FrozenCpuBridge*") }
    archiveClassifier.set("cpu-baseline")
}

val cpuDeviceSmoke by sourceSets.creating
cpuDeviceSmoke.compileClasspath += sourceSets.main.get().output
cpuDeviceSmoke.runtimeClasspath += sourceSets.main.get().output
configurations[cpuDeviceSmoke.implementationConfigurationName].extendsFrom(configurations.implementation.get())
tasks.register<Jar>("cpuDeviceSmokeJar") {
    from(cpuDeviceSmoke.output)
    archiveClassifier.set("cpu-device-smoke")
}

tasks.register<Test>("cpuArena") {
    description = "Compare the master CPU with the tactical baseline over paired, seeded real-deck matches."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching("*CpuArenaTest") }
    systemProperty("cpu.arena.games", providers.gradleProperty("cpuArenaGames").getOrElse("1000"))
    systemProperty("cpu.arena.threads", providers.gradleProperty("cpuArenaThreads").getOrElse("4"))
    systemProperty("cpu.arena.seedBase", providers.gradleProperty("cpuArenaSeedBase").getOrElse("1000003"))
    systemProperty("cpu.arena.traceGame", providers.gradleProperty("cpuArenaTraceGame").getOrElse("-1"))
    systemProperty("cpu.arena.baseline", providers.gradleProperty("cpuArenaBaseline").getOrElse("TACTICAL"))
    systemProperty("cpu.arena.knowledge", providers.gradleProperty("cpuArenaKnowledge").getOrElse("FAIR"))
    systemProperty("cpu.arena.baselineJar", providers.gradleProperty("cpuArenaBaselineJar").getOrElse(""))
    systemProperty("cpu.arena.tuning", providers.gradleProperty("cpuArenaTuning").getOrElse(".35,.15,80"))
    systemProperty("cpu.arena.deck", providers.gradleProperty("cpuArenaDeck").getOrElse(""))
    maxHeapSize = "2g"
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
    testLogging.showStandardStreams = true
    reports.junitXml.outputLocation.set(layout.buildDirectory.dir("cpu-arena/test-results"))
    reports.html.outputLocation.set(layout.buildDirectory.dir("cpu-arena/test-report"))
}
