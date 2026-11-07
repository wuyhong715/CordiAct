# CordiAct Architecture Decision: SSH & Termux Execution Backends

**Status:** Implemented  
**Date:** 2026-11-07  
**Context:** CordiAct requires direct execution backends beyond local Shizuku/root shell to support remote infrastructure control (SSH) and full-featured local Linux toolchains (Termux).

---

## 1. Design Principles & Modularity

- **Decoupled Contracts in `:core`**:
  - `ExecutionResult`: Unified cross-backend output format (`exitCode`, `stdout`, `stderr`, `timedOut`, `truncated`).
  - `SshHostConfig` & `SshExecutionService`: Specification for host authentication, key-pinning, execution, and SFTP transfers.
  - `TermuxContracts`: Execution parameters, inspection status, and interactive session models.
  - Seamless integration with `ShellCommandPolicy` for command security classification.
- **Pure Java SSH Engine (`com.github.mwiede:jsch:0.2.21`)**:
  - Zero NDK footprint, no native binaries to ship across Android ABIs.
  - Full support for modern OpenSSH keys (Ed25519, ECDSA, RSA-SHA2) and SFTP streaming.
- **Android Intent Bridge for Termux (`com.termux.permission.RUN_COMMAND`)**:
  - Uses the official `RunCommandService` with dynamic callback broadcast receivers.
  - Layered `TermuxSessionManager` supporting persistent interactive tmux sessions (with `waitForPattern` polling).

---

## 2. Tools Added

### SSH Backend (`com.cordiact.ssh`)
1. `ssh_save_host`: Store credentials with optional SHA256 key-pinning.
2. `ssh_list_hosts`: Retrieve desensitized summaries of configured hosts.
3. `ssh_delete_host`: Remove saved host configurations.
4. `ssh_exec`: Ad-hoc command execution without saving credentials.
5. `ssh_exec_saved`: Command execution on saved hosts.
6. `ssh_upload`: SFTP upload (UTF-8 text or Base64 binary).
7. `ssh_download`: SFTP download (as text or Base64).

### Termux Backend (`com.cordiact.termux`)
1. `termux_status`: Probe installation, RUN_COMMAND permission, and external app readiness.
2. `termux_run_command`: Fire-and-forget or synchronous command/script execution via Intent service.
3. `termux_session`: Interactive tmux session controller (`start`, `send`, `read`, `kill`, `list`).

---

## 3. Security Considerations
- SSH passwords and private keys are stored in private app storage (`/data/user/0/.../files/ssh_hosts.json`).
- Ad-hoc and saved command execution runs through `ShellCommandPolicy.classify(command)` before dispatch.
- Pinned fingerprints prevent Man-in-the-Middle (MITM) attacks during SSH connection establishment.
