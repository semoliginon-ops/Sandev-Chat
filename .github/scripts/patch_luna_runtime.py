#!/usr/bin/env python3
"""Port the known-good Luna llama.cpp and model-template path into Sandev Chat."""
from pathlib import Path
import sys

project = Path(sys.argv[1])
cmake_path = project / "app/src/main/cpp/CMakeLists.txt"
cmake = cmake_path.read_text(encoding="utf-8")
old_tag = "GIT_TAG b4514"
new_tag = "GIT_TAG a0e6dc7384edb8f5d927155e1d26c10b4a310e6d"
if old_tag in cmake:
    cmake = cmake.replace(old_tag, new_tag, 1)
elif new_tag not in cmake:
    raise SystemExit("Could not find expected llama.cpp tag in CMakeLists.txt")
extra_options = """set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)"""
if "set(LLAMA_BUILD_TOOLS OFF" not in cmake:
    anchor = 'set(LLAMA_CURL OFF CACHE BOOL "" FORCE)'
    if anchor not in cmake:
        raise SystemExit("Could not find expected llama.cpp CMake options")
    cmake = cmake.replace(anchor, anchor + "\n" + extra_options, 1)
cmake_path.write_text(cmake, encoding="utf-8")

native_target = project / "app/src/main/cpp/native-llama.cpp"
patch_native = Path(__file__).resolve().parent.parent / "patches/native-llama.cpp"
native_target.write_text(patch_native.read_text(encoding="utf-8"), encoding="utf-8")

html_path = project / "app/src/main/assets/index.html"
html = html_path.read_text(encoding="utf-8")
old_prompt = r'''let prompt='<|im_start|>system\n'+st+'\nBalas dalam bahasa Indonesia yang natural. Jika diminta ngobrol, jawaban boleh panjang dan ekspresif. Jangan menulis label USER atau ASSISTANT di jawaban. <|im_end|>\n';history.forEach(m=>{prompt+='<|im_start|>'+(m.role==='user'?'user':'assistant')+'\n'+m.text+'<|im_end|>\n'});prompt+='<|im_start|>assistant\n';let max= settings.mode==='auto' ? (settings.lengthStyle==='long'?384:192) : settings.tokens;Sandev.generate(prompt,max,settings.temp,settings.topP,settings.repeat)'''
new_prompt = r'''let prompt='[[SANDEV_SYSTEM]]\n'+st+'\nBalas dalam bahasa Indonesia yang natural. Jika diminta ngobrol, jawaban boleh panjang dan ekspresif. Jangan menulis label USER atau ASSISTANT di jawaban.';history.forEach(m=>{prompt+=(m.role==='user'?'\n[[SANDEV_HISTORY_USER]]\n':'\n[[SANDEV_HISTORY_ASSISTANT]]\n')+m.text});let max= Math.min(80,settings.mode==='auto' ? (settings.lengthStyle==='long'?384:192) : settings.tokens);Sandev.generate(prompt,max,settings.temp,settings.topP,settings.repeat)'''
if old_prompt in html:
    html = html.replace(old_prompt, new_prompt, 1)
elif "[[SANDEV_SYSTEM]]" not in html:
    raise SystemExit("Could not find expected hard-coded chat-template prompt in index.html")
html_path.write_text(html, encoding="utf-8")
print("Patched Sandev Chat to use Luna's model-aware chat template, bounded prompt and 80-token reply budget.")
