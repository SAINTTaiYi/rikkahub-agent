# RikkaHub Ultra Release Status

**Status date:** 2026-08-04
**Repository / branch:** \ / \

## Current delivery status

The Ultra standalone Android build has a signed, optimized, arm64-only Release APK.

| Item | Status |
| --- | --- |
| Standalone application ID | Complete: \ |
| arm64-only APK split | Complete: \ only |
| Release optimization | Complete: R8/ProGuard and resource shrinking enabled |
| Release signing | Complete: verified against the local Release signing identity |
| CI build | Complete: [GitHub Actions run 30803158343](https://github.com/SAINTTaiYi/rikkahub-agent/actions/runs/30803158343) |
| Artifact checksum | Complete: SHA-256 verified |
| Duplicate GitHub Release publication | Not performed intentionally |

## Release artifact

The successful build was produced from commit [\](https://github.com/SAINTTaiYi/rikkahub-agent/commit/646bc39f400bd5dba7f7a1647aecc26d4dd9dac7).

- CI artifact name: \
- APK output: \
- Package ID: \
- APK size: 60144828 bytes
- SHA-256: \
- Native ABI verification: 17 native libraries, all under \
- Signature verification: APK Signature Scheme v2 is valid; its signer certificate matches the local Release PKCS#12 certificate.

The signing workflow uses ephemeral GitHub Actions Secrets and removes temporary signing files at the end of the job. Secret values and signing credentials are not stored in this repository.

## Agent capability progress

### Long-running / tool-loop capability

The previous 32-tool-call limitation is no longer the app-level limit. A device test completed **42 serial tool rounds** successfully:

- 1 cleanup operation
- 40 write operations
- 1 count operation

This verifies the expected long agent/tool-loop behavior beyond the prior 32-call boundary. Practical limits still include model context, provider limits, execution timeout, and user cancellation.

### Ultra reasoning profile

The Ultra profile is implemented as the high reasoning tier:

- Internal Ultra reasoning budget: 64K
- OpenAI/Codex-compatible provider mapping: \

The remaining recommended device-side validation is to select **Ultra** in the app with a provider that exposes request logs, then confirm that the provider receives the \ request field. Providers that do not support that field may ignore or map it to their own reasoning controls.

## Backup restore compatibility (Grok)

A legacy local backup could contain a provider entry with `"type":"grok"`. The current app no longer has a dedicated Grok `ProviderSetting` serializer, which previously made the entire settings restore fail at provider deserialization.

The compatibility migration now:

1. Converts legacy `grok` provider entries into the supported `openai` provider format.
2. Preserves the common provider fields, including provider ID, name, enabled state, models, and balance options.
3. Uses `https://api.x.ai/v1` as the default endpoint only when the legacy entry has no endpoint value.
4. Skips malformed or unsupported provider entries so they cannot prevent the rest of a backup from restoring.

Regression coverage was added in `SettingsJsonMigratorTest`. The fix was implemented in commits [`3aed39e3d`](https://github.com/SAINTTaiYi/rikkahub-agent/commit/3aed39e3d0e280eda4107126c8e823788453d05d) and [`ef4d7538c`](https://github.com/SAINTTaiYi/rikkahub-agent/commit/ef4d7538ce8fd6ecd9befded44a361c81a87271d), then validated by [GitHub Actions run 30847129762](https://github.com/SAINTTaiYi/rikkahub-agent/actions/runs/30847129762).

### Verified restore-fix artifact

- APK: `RikkaHub-Ultra-arm64-grok-restore-fix.apk`
- Package ID: `me.rerere.rikkahub.longagent`
- ABI: `arm64-v8a` only
- APK size: 60,145,216 bytes
- SHA-256: `d470e5e7d83ab622960f6589273ebb6d33d79961e606f25c2c3b6acdaed3a31f`
- Signature: verified with `apksigner`

After installing this build, retry importing the same local backup. The legacy Grok provider should appear as an OpenAI-compatible xAI provider rather than causing the settings restore to fail.

## Termux compatibility

The ordinary Termux command-capture compatibility fix is included in the branch history. The recommended follow-up device test is a normal non-interactive Termux command and confirmation that its captured stdout/stderr is returned in the tool result.

## Build workflow safeguards

The dedicated workflow [\](../.github/workflows/release-ultra-standalone.yml) now:

1. Materializes signing inputs only inside the CI runner.
2. Builds \.
3. Verifies the resulting APK signature with \.
4. Asserts that the native ABI set is exactly \.
5. Produces a \ file alongside the uploaded artifact.
6. Removes temporary signing material even when the job fails.
