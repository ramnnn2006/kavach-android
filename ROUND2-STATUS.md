# Round 2 scope and verification

## Implemented

Client: dark/light action contrast and warning text; decorative type icons; spinner buttons retain labels; admin validation live announcements and scroll-to-error; tower field error; member/team drafts survive recreation; tracker draft and cancel visibility saved; accessible SOS click action label; Android 14 full-screen alert settings from setup/profile; offline action failure restores notification buttons without expiry; safety-check lock-screen privacy; outbox retries after session expiry and clears directly delivered rows; signed-out unsent count; Keystore failure uses process-memory preferences, not plaintext tokens; release tasks require signing configuration; expired notices visible to admins; assignment request includes expected assignee; bottom tab labels allow two lines. One client_id per form and in-flight submit blocking were already present and retained. Note-only tracker edits also send the existing people count as a defence before backend deployment.

SQL: approved-phone signup guard before existing provisioning; note-only people preservation; help dedupe with advisory lock and unique open-incident index; safe flip cancels pending linked incidents or annotates assigned work; flat spoof rejection; existing-flat-only home setup and occupancy check; flat inventory/uniqueness guards; profile demotion/specialty protection with active work; one active safety check; own/last-admin approved-list removal guard; note/people limits; adjacent advance; claim privacy ordering/idempotency; assignment resets acknowledgment, checks duty/specialty and expected assignee; past notice expiry refused; responder specialties nonempty; member name validation; source-grounded privilege boundaries retained.

## Deliberately not claimed fixed

- Realtime private-channel authorization requires coordinated web/Android channel configuration, topic policy design and dashboard rollout. Runbook describes it; no blanket policy installed.
- Full row data can still be included in raw PostgREST constraint `details`. Android never displays raw details, and RPC checks cover common inputs; changing the HTTP error surface needs a backend gateway/error-hook decision.
- The newer deployed approved-member RPC definitions are not in public source. Row guards cover their effects, but their early 300-character name error can still be generic. The existing phone provisioning and update_member bodies are preserved to avoid erasing newer synchronization logic.
- 200% font top bars, small-landscape CommandBoard layout, full native screen visuals, TalkBack traversal, notification timing/OEM behavior and process-death interaction paths still require device verification. Two-line bottom labels are a mitigation, not a full large-font pass.
- Not every admin sheet outside members/team was migrated to saved state.
- No automatic public server changes or audit-data deletion performed. Owner must complete dashboard steps and stage/test/apply SQL.

## Intended behavior retained

Optional report floor; blank SOS descriptions; off-duty self-claim; admin claim specialty bypass; responder society-contact read access. Narrower contact visibility is a separate hardening option.

## Evidence

151 debug unit tests pass (147 existing + four new memory-storage/contrast tests). The migration was applied in a disposable PostgreSQL instance based on the source schema plus the live-read members columns. Rollback-only regression checks passed for signup guard, note-only people count, length guard, claim idempotency, no skipped states, active-work demotion refusal, one active check, repeated-help dedupe and safe cancellation. This is not production Supabase verification.

A static rendered palette sample was visually inspected and AA ratios asserted in unit tests. It is not an Android screenshot. No emulator/device visual pass was possible on this build host.

Final build: `assembleDebug` succeeded with one worker and SerialGC (1050 MB heap). Final `testDebugUnitTest` succeeded, 151 tests, zero failures/errors. `assembleRelease --dry-run` without release credentials failed with the intended signing-configuration error. The packaged debug APK is for testing, not a release-signed artifact.
