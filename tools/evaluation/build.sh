#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
export JAVA_HOME="${IPLOC_JDK_HOME:-/usr/lib/jvm/java-11-openjdk-amd64}"
export MAVEN_USER_HOME="${IPLOC_MAVEN_HOME:-/tmp/iploc-maven-home}"
exec ./mvnw -Dmaven.repo.local="${IPLOC_MAVEN_REPO:-/tmp/iploc-m2}" "$@"
