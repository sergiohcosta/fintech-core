#!/usr/bin/env python3
"""Hook PreToolUse: bloqueia Edit/Write em código quando a branch é develop/main.

Docs, specs, planos e scripts continuam liberados — só caminhos de código
(backend/src, frontend/src, api-spec) exigem worktree (ver git-operator.md).
"""
import json
import os
import subprocess
import sys

try:
    file_path = json.load(sys.stdin).get("tool_input", {}).get("file_path", "")
except Exception:
    sys.exit(0)

if not file_path or not any(
    s in file_path for s in ("/backend/src/", "/frontend/src/", "/api-spec/")
):
    sys.exit(0)

d = os.path.dirname(file_path)
while d != "/" and not os.path.isdir(d):
    d = os.path.dirname(d)

try:
    branch = subprocess.run(
        ["git", "-C", d, "branch", "--show-current"],
        capture_output=True, text=True,
    ).stdout.strip()
except Exception:
    sys.exit(0)

if branch in ("develop", "main"):
    print(json.dumps({
        "hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": "deny",
            "permissionDecisionReason": (
                f"Branch '{branch}' — código não é editado aqui. "
                "Crie uma worktree a partir de develop (git-operator.md)."
            ),
        }
    }))
