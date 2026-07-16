#!/usr/bin/env bash
set -euo pipefail
export PATH="$HOME/.local/bin:$PATH"

echo "Deleting Kind cluster 'ledgerflow-cluster'..."
kind delete cluster --name ledgerflow-cluster
echo "Cleanup complete."
