# SANDEV Life — Local Model Game

SANDEV Life is a separate life-simulation prototype in this branch. It loads the existing **SANDEV Game AI GGUF** locally; it does not call a hosted AI service and does not change SANDEV Chat application code.

## Run on a computer

1. Download the existing Actions artifact named **SANDEV-Game-AI-GGUF-Q4_K_M** from the previously completed training run and extract `SANDEV-Game-AI-Q4_K_M.gguf`.
2. Install Python 3.10+.
3. From this folder, run `start_windows.bat` on Windows, or `./start_local.sh /full/path/to/SANDEV-Game-AI-Q4_K_M.gguf` on Linux/macOS.
4. The first launch installs `llama-cpp-python` if it is missing, then loads the model into local RAM.
5. Open **http://127.0.0.1:8080** on the same device and click **Tes koneksi model**.

The GGUF artifact is not committed to Git because it is a generated binary. It remains in GitHub Actions artifacts until its expiration date; download it before it expires.

## How actions work

The training script teaches a general decision protocol: the model receives a game state and a list of legal candidate actions with normalized features, then returns `ACTION: <id>`. Each game turn builds a fresh candidate list and maps the selected candidate ID to the corresponding in-game action. The game validates the returned ID before applying it. This avoids incorrectly assuming that a particular ID such as `act_202` always means “eat” or “sleep”.

The prototype translates life-simulation needs into the trained model's generic fields (health, resources, threat, uncertainty, objective urgency). Candidate features are transparent game-side estimates. They are not additional model training.

## Important limitations

- This is a prototype. The original model was trained on synthetic, heuristic-labelled game decisions across several game genres, not specifically on life-simulation trajectories.
- It must be benchmarked across multiple simulated days; model output may be poor or malformed.
- The runtime is local to the computer running the server. Opening this page on a different device does not make the model run on that device.
- If `llama-cpp-python` cannot install a compatible wheel on your operating system, install a compatible runtime/build for your Python version as described by the package documentation.
- No GitHub Actions workflow is needed to run the game.