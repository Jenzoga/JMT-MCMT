#!/usr/bin/env bash
# Rebuilds the Forge 1.12.2 userdev jar that is no longer published upstream.
# Upstream FG 2.3 resolves net.minecraftforge:forge:<ver>:userdev, which was
# purged from maven.minecraftforge.net. This script synthesizes it from the
# two artifacts that survived (universal + repacked installer) and drops it
# into the gradle cache path FG searches.
#
# Usage: ./etc/buildscripts/gen-userdev.sh   (run once per machine)
set -euo pipefail

MC_VER=1.12.2
FORGE_VER=14.23.5.2860
V="${MC_VER}-${FORGE_VER}"
BASE="https://maven.minecraftforge.net/net/minecraftforge/forge/${V}"
WORK="$(mktemp -d)"
CACHE="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/minecraft/deobfedDeps/net/minecraftforge/forge/${V}"

trap 'rm -rf "$WORK"' EXIT

curl -fsSL -o "$WORK/universal.jar" "$BASE/forge-${V}-universal.jar"
curl -fsSL -o "$WORK/installer.jar" "$BASE/forge-${V}-installer.jar"

cd "$WORK"
unzip -qo universal.jar binpatches.pack.lzma forge_at.cfg
cp universal.jar classes.jar
unzip -po installer.jar version.json > dev.json
mv binpatches.pack.lzma devbinpatches.pack.lzma
cp forge_at.cfg merged_at.cfg
python3 - <<'EOF'
import zipfile
for name in ('patches.zip', 'sources.zip', 'resources.zip'):
    zipfile.ZipFile(name, 'w').close()
open('merged.srg', 'w').close()
open('merged.exc', 'w').close()
EOF
zip -qX "forge-${V}-userdev.jar" dev.json devbinpatches.pack.lzma classes.jar \
    patches.zip sources.zip resources.zip merged_at.cfg merged.srg merged.exc

mkdir -p "$CACHE"
cp "forge-${V}-userdev.jar" "$CACHE/"
echo "installed userdev -> $CACHE/forge-${V}-userdev.jar"
