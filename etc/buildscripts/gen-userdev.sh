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

# Forge's CDN is flaky; keep downloads in a persistent cache and retry.
STASH="${XDG_CACHE_HOME:-$HOME/.cache}/mcmt-forge-artifacts/${V}"
mkdir -p "$STASH"
fetch() { # fetch <name> <url>
    local name="$1" url="$2"
    if [ ! -s "$STASH/$name" ]; then
        curl -fsSL --retry 5 --retry-delay 3 --max-time 600 -o "$STASH/$name.part" "$url" \
            && mv "$STASH/$name.part" "$STASH/$name"
    fi
    cp "$STASH/$name" "$WORK/$name"
}
fetch universal.jar "$BASE/forge-${V}-universal.jar"
fetch installer.jar "$BASE/forge-${V}-installer.jar"

cd "$WORK"
unzip -qo universal.jar binpatches.pack.lzma forge_at.cfg
# classes.jar must not carry binpatches.pack.lzma: FG applies the patches at
# build time, and shipping it inside would make runtime ClassPatchManager
# double-apply and abort with a checksum mismatch.
cp universal.jar classes.jar
zip -qd classes.jar binpatches.pack.lzma 'deobfuscation_data-*'
# The repacked installer's version.json lost Mojang's jar download URLs and
# assetIndex; FG skips the MC jar downloads without them, so binpatches never
# land in forgeBin. Merge them back in from Mojang's manifest.
fetch mojang.json "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json"
MOJANG_URL=$(python3 - "$STASH/mojang.json" <<'EOF'
import json, sys
man = json.load(open(sys.argv[1]))
print(next(v["url"] for v in man["versions"] if v["id"] == "1.12.2"))
EOF
)
fetch mojang-1.12.2.json "$MOJANG_URL"
unzip -po installer.jar version.json > dev.json
python3 - "$STASH/mojang-1.12.2.json" dev.json <<'EOF'
import json, sys
mv = json.load(open(sys.argv[1]))
d = json.load(open(sys.argv[2]))
for key in ("downloads", "assetIndex", "javaVersion"):
    if key in mv:
        d.setdefault(key, mv[key])
json.dump(d, open(sys.argv[2], 'w'), indent=1)
EOF
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

# The plain forge artifact resolves onto the dev classpath. Ship it stripped of
# the embedded patch pack, or ClassPatchManager double-patches at runtime.
cp universal.jar "forge-${V}.jar"
zip -qd "forge-${V}.jar" binpatches.pack.lzma 'deobfuscation_data-*'

mkdir -p "$CACHE"
cp "forge-${V}-userdev.jar" "$CACHE/"
cp "forge-${V}.jar" "$CACHE/"
echo "installed userdev -> $CACHE/forge-${V}-userdev.jar"
