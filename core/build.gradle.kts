plugins {
    alias(libs.plugins.kotlin.jvm)
}

java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(libs.zxing.core) // QR codes for wristbands and the police key
    testImplementation(libs.junit)
}

// Train the recovery-policy model from simulated links and write it into the resources the app ships.
tasks.register<JavaExec>("trainPolicy") {
    group = "linkfill"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("app.linkfill.core.sim.TrainKt")
    args(file("src/main/resources/app/linkfill/core/policy/recovery_policy_v1.mlp").absolutePath)
}

// Compare the delivery strategies on held-out simulated links and write the results report.
tasks.register<JavaExec>("runExperiment") {
    group = "linkfill"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("app.linkfill.core.sim.ExperimentKt")
    args(rootProject.file("docs/RESULTS.md").absolutePath)
}

// Train the link-loss predictor (ablations on held-out simulated routes) and write the model and report.
tasks.register<JavaExec>("trainLinkModel") {
    group = "linkfill"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("app.linkfill.core.sim.TrainLinkModelKt")
    args(file("src/main/resources/app/linkfill/core/mesh/loss_predictor_v1.txt").absolutePath, rootProject.file("docs/LINK_PREDICTOR_RESULTS.md").absolutePath)
}

// Flood a crew message across a simulated crowd at several app-adoption levels and write the report.
tasks.register<JavaExec>("runMeshRange") {
    group = "linkfill"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("app.linkfill.core.sim.MeshRangeExperimentKt")
    args(rootProject.file("docs/MESH_RANGE_RESULTS.md").absolutePath)
    maxHeapSize = "3g"
}

// Compare old vs Phase 4 crowd routing rules at 5, 20, 100, and 500 nodes in simulation.
tasks.register<JavaExec>("runCrowdRouting") {
    group = "linkfill"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("app.linkfill.core.sim.CrowdRoutingExperimentKt")
    maxHeapSize = "3g"
}

// Phase 10: Run event-level calibrated simulator against real phone traces.
tasks.register<JavaExec>("runCalibratedSim") {
    group = "linkfill"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("app.linkfill.core.sim.BlePhyEventSimulatorKt")
    args(rootProject.file("docs/research/MESH_SIM_RESULTS.md").absolutePath)
    maxHeapSize = "3g"
}


// Create the authority (police) signing key: public half bundled in the app, private half + QR in secrets/.
tasks.register<JavaExec>("makeAuthorityKey") {
    group = "linkfill"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("app.linkfill.core.tools.AuthorityKeysKt")
    args(file("src/main/resources/app/linkfill/core/mesh/authority_public.txt").absolutePath, rootProject.file("secrets").absolutePath)
}
