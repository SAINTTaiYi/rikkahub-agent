# RikkaHub Ultra Release Status

**Status date:** 2026-08-03
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
