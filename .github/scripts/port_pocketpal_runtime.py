#!/usr/bin/env python3
"""Generate a React Native host using PocketPal's llama.rn engine and Sandev's UI assets."""
from pathlib import Path
import json, re, shutil, subprocess, zipfile

root = Path.cwd()
work = root / ".migration-work"
archive = root / "sandev-chat-alpha-0.2.zip"
if not archive.exists():
    raise SystemExit("Sandev source archive is missing")
if work.exists():
    shutil.rmtree(work)
(work / "legacy").mkdir(parents=True)

with zipfile.ZipFile(archive) as z:
    z.extractall(work / "legacy")
legacy = work / "legacy"
htmls = list(legacy.rglob("index.html"))
if not htmls:
    htmls = list(legacy.rglob("*.html"))
if not htmls:
    raise SystemExit("No HTML UI found in Sandev source archive")
html = sorted(htmls, key=lambda p: ("src/main/assets" not in p.as_posix(), p.name != "index.html", len(p.parts)))[0]
assets = html.parent
print("Preserving Sandev UI:", html.relative_to(legacy))

subprocess.run(["npx", "--yes", "@react-native-community/cli@latest", "init", "SandevChat", "--version", "0.82.1", "--skip-install"], cwd=work, check=True)
app = work / "SandevChat"
dest = app / "android/app/src/main/assets/sandev"
dest.mkdir(parents=True, exist_ok=True)
shutil.copytree(assets, dest, dirs_exist_ok=True)

# Adapt the old synchronous callsite for the asynchronous React Native WebView bridge.
entry = dest / html.name
source = entry.read_text(encoding="utf-8")
# Replace the old Llama-specific prompt wrapper with structured role markers so
# llama.rn can apply the GGUF's own chat template, as PocketPal does.
generate_match = re.search(r"(?:Sandev|Android)\.generate\(prompt,max,settings\.temp,settings\.topP,settings\.repeat\)", source)
if not generate_match:
    raise SystemExit("Could not locate the original generate(prompt, max, ...) call")
prompt_start = source.rfind("let prompt=", 0, generate_match.start())
statement_end = source.find(";", generate_match.start())
if prompt_start < 0 or statement_end < 0:
    raise SystemExit("Could not isolate Sandev prompt-building statement")
new_prompt = r"""let prompt='[[SANDEV_SYSTEM]]\n'+st+'\nBalas dalam bahasa Indonesia yang natural. Jika diminta ngobrol, jawaban boleh panjang dan ekspresif. Jangan menulis label USER atau ASSISTANT di jawaban.';history.forEach(m=>{prompt+=(m.role==='user'?'\n[[SANDEV_HISTORY_USER]]\n':'\n[[SANDEV_HISTORY_ASSISTANT]]\n')+m.text});let max= settings.mode==='auto' ? (settings.lengthStyle==='long'?384:192) : Math.min(512,settings.tokens);Sandev.generate(prompt,max,settings.temp,settings.topP,settings.repeat);"""
source = source[:prompt_start] + new_prompt + source[statement_end + 1:]
print("Patched Sandev prompt to use model-native GGUF chat templates.")
old = "Sandev.generate("
if old not in source:
    old = "Android.generate("
if old not in source:
    raise SystemExit("Expected generate() call not found; refusing to ship a broken UI bridge")
source = source.replace(old, "await " + old, 1)
pos = source.index("await " + old)
arrow = source.rfind("=>", 0, pos)
fn = source.rfind("function", 0, pos)
if arrow > fn:
    prefix = source[:arrow]
    close = prefix.rfind(")")
    depth, opening = 0, -1
    for i in range(close, -1, -1):
        if prefix[i] == ")": depth += 1
        elif prefix[i] == "(":
            depth -= 1
            if depth == 0:
                opening = i
                break
    if opening >= 0 and "async" not in prefix[max(0, opening - 10):opening]:
        source = source[:opening] + "async " + source[opening:]
elif fn >= 0 and "async" not in source[max(0, fn - 8):fn]:
    source = source[:fn] + "async " + source[fn:]
entry.write_text(source, encoding="utf-8")

# Copy the maintained host source from the repository into the generated RN template.
for rel in ["App.tsx", "index.js", "app.json", "tsconfig.json"]:
    src = root / ".github/pocketpal-host" / rel
    if src.exists():
        shutil.copy2(src, app / rel)

package = json.loads((app / "package.json").read_text(encoding="utf-8"))
package["name"] = "sandev-chat"
package["dependencies"].update({
    "@pocketpalai/llama.rn": "0.8.1-0",
    "@react-native-async-storage/async-storage": "2.2.0",
    "react-native-document-picker": "9.3.1",
    "react-native-fs": "2.20.0",
    "react-native-webview": "13.16.0"
})
(app / "package.json").write_text(json.dumps(package, indent=2) + "\n", encoding="utf-8")
manifest = app / "android/app/src/main/AndroidManifest.xml"
text = manifest.read_text(encoding="utf-8").replace('android:label="SandevChat"', 'android:label="Sandev Chat"')
manifest.write_text(text, encoding="utf-8")
rules = app / "android/app/proguard-rules.pro"
rules.write_text(rules.read_text(encoding="utf-8") + "\n# PocketPal llama.rn native bridge\n-keep class com.rnllama.** { *; }\n", encoding="utf-8")
print("Generated React Native + llama.rn app at", app)
