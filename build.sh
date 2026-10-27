#!/bin/sh
 JAVA_TOOL_OPTIONS="-Dos.name=Linux -Dorg.sqlite.lib.path=/usr/local/lib -Dorg.sqlite.lib.name=libsqlitejdbc.so" ./gradlew assembleRelease --no-daemon "$@"