#!/usr/bin/env bash
# Download vale styles and create a Grazie configuration for the IntelliJ Grazie Pro plugin
vale init
for f in .github/styles/RedHat/*.yml; do cat -- "$f"; printf "\n"; done > .grazie.en.yml
