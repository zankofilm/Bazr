# Camera / Evidence chain hardening — v1.9.3

This patch extends the v1.9.2 FileProvider/CameraX fix through the full evidence lifecycle:

1. CameraX writes directly to app-private `files/evidence/<mission>/` storage.
2. Captured JPEG is oriented, resized, timestamped and atomically replaced through a temporary processing file.
3. Imported documents are copied atomically into the same private evidence tree and rejected if empty/incomplete.
4. Draft restore filters missing/zero-length evidence instead of treating broken paths as valid attachments.
5. Draft save now preserves the general note consistently.
6. Final submission writes a complete local draft snapshot before opening the network session.
7. A preflight check verifies all evidence and the signature still exist before any upload begins.
8. Upload sends `captured_at` from the local file timestamp and validates the server's returned `file_id`.
9. The draft is removed only after the official PDF is successfully generated/uploaded/attached.
10. If PDF synchronization fails after form submission, the local draft/evidence snapshot is retained for recovery.

Version: 1.9.3 (versionCode 19)
