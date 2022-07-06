#!/bin/bash
# This is a workaround for setting the ulimit for files for all already running proceses.
# I didn't find a setting in minikube to make that permanent.
minikube ssh << EOF
sudo bash
for pid in \$(ps xa -o pid=); do prlimit --pid \$pid --nofile=102400:102400; done
exit 0
exit
EOF
