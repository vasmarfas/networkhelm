#!/bin/sh
# Gradle for the release workflows, run up to three times: a runner now and then loses a download or runs out of memory
# in the middle of a build, and the next attempt gets past it. Before it the daemons are stopped, which gives their memory
# back, and the tasks run one at a time. What an attempt built stays up to date and is not built again.
# Usage: sh tools/release/gradle.sh <arguments of gradlew>

./gradlew "$@" && exit 0
case " $* " in
    *" --max-workers="*) serial= ;;
    *) serial=--max-workers=1 ;;
esac
for attempt in 2 3; do
    echo "::warning::Gradle failed, attempt $attempt of 3 starts with fresh daemons and runs one task at a time"
    ./gradlew --stop > /dev/null 2>&1
    for pid in $(jps -l 2> /dev/null | awk '/KotlinCompileDaemon/ { print $1 }'); do
        kill "$pid" 2> /dev/null || taskkill //F //PID "$pid" > /dev/null 2>&1
    done
    sleep $((attempt * 15))
    ./gradlew $serial "$@" && exit 0
done
exit 1
