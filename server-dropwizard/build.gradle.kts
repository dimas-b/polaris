/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
  id("polaris-server")
  id("polaris-license-report")
  id("polaris-shadow-jar")
  id("application")
}

dependencies {
  implementation(project(":polaris-core"))
  implementation(project(":polaris-service"))

  implementation(platform(libs.dropwizard.bom))
  implementation("io.dropwizard:dropwizard-core")
  implementation("io.dropwizard:dropwizard-auth")
  implementation("io.dropwizard:dropwizard-json-logging")

  implementation(libs.prometheus.metrics.exporter.servlet.jakarta)
  implementation(platform(libs.micrometer.bom))
  implementation("io.micrometer:micrometer-core")
  implementation("io.micrometer:micrometer-registry-prometheus")

  implementation(platform(libs.opentelemetry.bom))
  implementation("io.opentelemetry:opentelemetry-api")
  implementation("io.opentelemetry:opentelemetry-sdk-trace")
  implementation("io.opentelemetry:opentelemetry-exporter-logging")
  implementation(libs.opentelemetry.semconv)

  implementation(platform(libs.iceberg.bom))
  implementation("org.apache.iceberg:iceberg-api")
  implementation("org.apache.iceberg:iceberg-core")
  implementation("org.apache.iceberg:iceberg-aws")

  implementation(platform(libs.google.cloud.storage.bom))
  implementation("com.google.cloud:google-cloud-storage")
  implementation(platform(libs.awssdk.bom))
  implementation("software.amazon.awssdk:sts")
  implementation("software.amazon.awssdk:iam-policy-builder")
  implementation("software.amazon.awssdk:s3")
  implementation(platform(libs.azuresdk.bom))
  implementation("com.azure:azure-core")

  implementation(libs.hadoop.common) {
    exclude("org.slf4j", "slf4j-reload4j")
    exclude("org.slf4j", "slf4j-log4j12")
    exclude("ch.qos.reload4j", "reload4j")
    exclude("log4j", "log4j")
    exclude("org.apache.zookeeper", "zookeeper")
  }

  testImplementation(libs.auth0.jwt)

  testImplementation("io.dropwizard:dropwizard-testing")
  testImplementation(libs.threeten.extra)

  testImplementation(platform(libs.junit.bom))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testImplementation(libs.assertj.core)
  testImplementation(libs.mockito.core)

  testImplementation("org.apache.iceberg:iceberg-api:${libs.versions.iceberg.get()}:tests")
  testImplementation("org.apache.iceberg:iceberg-core:${libs.versions.iceberg.get()}:tests")
  testImplementation("org.apache.iceberg:iceberg-spark-3.5_2.12")
  testImplementation("org.apache.iceberg:iceberg-spark-extensions-3.5_2.12")
  testImplementation("org.apache.spark:spark-sql_2.12:3.5.1") {
    // exclude log4j dependencies
    exclude("org.apache.logging.log4j", "log4j-slf4j2-impl")
    exclude("org.apache.logging.log4j", "log4j-api")
    exclude("org.apache.logging.log4j", "log4j-1.2-api")
  }

  testImplementation("software.amazon.awssdk:glue")
  testImplementation("software.amazon.awssdk:kms")
  testImplementation("software.amazon.awssdk:dynamodb")

  testImplementation(platform(libs.testcontainers.bom))
  testImplementation("org.testcontainers:testcontainers")
  testImplementation(libs.s3mock.testcontainers)
}

application { mainClass = "org.apache.polaris.service.PolarisApplication" }

tasks.named<Test>("test").configure {
  if (System.getenv("AWS_REGION") == null) {
    environment("AWS_REGION", "us-west-2")
  }
  jvmArgs("--add-exports", "java.base/sun.nio.ch=ALL-UNNAMED")
  useJUnitPlatform()
  maxParallelForks = 4
}

tasks.register<Jar>("testJar") {
  archiveClassifier.set("tests")
  from(sourceSets.test.get().output)
}

tasks.named<Jar>("jar") {
  manifest { attributes["Main-Class"] = "org.apache.polaris.service.PolarisApplication" }
}

val shadowJar =
  tasks.named<ShadowJar>("shadowJar") {
    manifest { attributes["Main-Class"] = "org.apache.polaris.service.PolarisApplication" }
    mergeServiceFiles()
    isZip64 = true
    finalizedBy("startScripts")
  }

val startScripts =
  tasks.named<CreateStartScripts>("startScripts") {
    classpath = files(provider { shadowJar.get().archiveFileName })
  }

tasks.register<Sync>("prepareDockerDist") {
  into(project.layout.buildDirectory.dir("docker-dist"))
  from(startScripts) { into("bin") }
  from(shadowJar) { into("lib") }
  doFirst { delete(project.layout.buildDirectory.dir("regtest-dist")) }
}

tasks.named("build").configure { dependsOn("prepareDockerDist") }

tasks.named("assemble").configure { dependsOn("testJar") }