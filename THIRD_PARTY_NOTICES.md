# Third-party notices

Quotile's own source and documentation are distributed under the
[Apache License 2.0](LICENSE). Third-party materials retain their original
licenses and notices. Trademarks are not licensed as Quotile branding.

## OpenAI Codex: authentication and quota protocol reference

The Android implementation references the public
[OpenAI Codex rust-v0.153.4 source](https://github.com/openai/codex/tree/rust-v0.153.4),
copyright 2025 OpenAI, licensed under Apache-2.0. Relevant source areas include:

- `codex-rs/login/`: native OAuth/PKCE, device-code authorization and token handling.
- `codex-rs/backend-client/src/client/`: authenticated quota requests and reset-credit endpoints.
- `codex-rs/backend-client/src/types.rs`: quota windows and reset-credit summaries.

Quotile implements these flows in Java for Android, with Android Keystore storage,
bounded/cancellable login and refresh sessions, and a native home-screen widget.
It does not bundle the Codex CLI, Rust terminal UI, or Ratatui.
The relevant Java files retain their protocol-provenance comments.

The upstream [license text](licenses/OpenAI-Codex-Apache-2.0.txt) and
[original NOTICE](licenses/OpenAI-Codex-NOTICE.txt) are retained for reference.
The Ratatui notice inside that original NOTICE describes the upstream Codex
distribution, not an additional dependency shipped by this Android app.

## Simple Icons: service-source mark

The small OpenAI Blossom mark beside the widget's Codex quota source label uses
the unmodified SVG path from [Simple Icons 9.20.0, OpenAI](https://github.com/simple-icons/simple-icons/blob/9.20.0/icons/openai.svg),
converted to Android VectorDrawable syntax without changing its geometry.
Simple Icons distributes its icon collection under [CC0 1.0](https://github.com/simple-icons/simple-icons/blob/9.20.0/LICENSE.md).
An unmodified local copy is included in [licenses/Simple-Icons-CC0-1.0.txt](licenses/Simple-Icons-CC0-1.0.txt).

OpenAI, ChatGPT, Codex and the Blossom mark belong to OpenAI. The mark identifies
the service supplying quota data; Quotile is an independent application.
See [OpenAI's brand guidelines](https://openai.com/brand/).

## Build tools

Android SDK/Build Tools, Gradle and GitHub Actions are used to build and test the
application. Their own licenses apply. The separately downloaded Android signing
tool used by CI is not included in the end-user download bundle. Signing private
keys and passwords are never distributed.
