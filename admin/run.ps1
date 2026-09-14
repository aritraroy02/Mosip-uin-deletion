# Launch the MOSIP data admin browser.
# Requires the Docker stack in ../docker to be running.
python -m pip install --quiet -r "$PSScriptRoot\requirements.txt"
python "$PSScriptRoot\server.py"
