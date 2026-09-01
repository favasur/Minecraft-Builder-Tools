#!/bin/bash
set -e
cd "$(git rev-parse --show-toplevel)"
rm -rf build/forgecheck
mkdir -p build/forgecheck
# Copy all forge sources (the fullslabs package is now a clean no-op adapter; only the shared
# FullSlabs class, which lives outside the forge source dir, needs stubbing below).
rsync -a src/forge/main/java/ build/forgecheck/ 2>/dev/null || (mkdir -p build/forgecheck && cd src/forge/main/java && find . -type d -exec mkdir -p ../../../../build/forgecheck/{} \; && find . -type f -name '*.java' -exec cp {} ../../../../build/forgecheck/{} \; && cd "$(git rev-parse --show-toplevel)")
# The real forge SlabRegistry is now a clean no-op adapter (no missing mixed-slab classes), so
# only the FullSlabs class needs stubbing for the classpath-only check.
mkdir -p build/forgecheck/io/github/favasur/fullslabs
cat > build/forgecheck/io/github/favasur/fullslabs/FullSlabs.java <<'JAVA'
package io.github.favasur.fullslabs;
public final class FullSlabs {
    public static void init() {}
    public static String id(net.minecraft.world.level.block.Block b) { return ""; }
    public static String verticalPath(net.minecraft.world.level.block.state.BlockState s) { return ""; }
}
JAVA
# Classpath: forge 1.21.1 userdev + minecraft + all deps. Use the CLASSPATH env var with a
# sources-only argfile (the full -cp arg exceeds the Windows command-line limit).
MC=$(find ~/.gradle/caches/forge_gradle/minecraft_user_repo -name "*_mapped_official_1.21.1.jar" 2>/dev/null | head -1)
EXTRA=$(find ~/.gradle/caches/modules-2/files-2.1 -name "*.jar" ! -name "*sources*" 2>/dev/null | tr '\n' ':')
export CLASSPATH="$MC:$EXTRA"
rm -rf build/forgecheck/out && mkdir -p build/forgecheck/out
find build/forgecheck -name '*.java' > build/forgecheck/sources.txt
javac -proc:none -d build/forgecheck/out @build/forgecheck/sources.txt 2>&1 | grep -E "error:" | head -20
echo "FORGE-JAVAC-EXIT: ${PIPESTATUS[0]}"
