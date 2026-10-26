# Security Specification (`security_spec.md`)

## 1. Data Invariants
1. **Strict Ownership Isolation**: Every `HomeNodeInfo` document at `/users/{userId}/home_node/{nodeId}` belongs exclusively to `userId`. Only an authenticated user with `request.auth.uid == userId` may read, create, update, or delete their home node state.
2. **Identity Integrity**: `incoming().userId` must strictly equal `request.auth.uid` and `userId` on creation, and remain immutable on updates (`incoming().userId == existing().userId`).
3. **Zero Cloud Secret Storage (Phase 1.6)**: `secretToken` is strictly prohibited in Firestore documents (`!('secretToken' in data)`). Random 32-byte tokens are stored exclusively on-device in Android Keystore (`AES/GCM/NoPadding` 256-bit key in `noBackupFilesDir`) and compared in constant time via `MessageDigest.isEqual`.
4. **Network & Hardware Telemetry Bounds**:
   - `deviceName`: string, 1..120 chars
   - `ipAddress`: string, 0..64 chars
   - `port`: int, 1024..65535
   - `batteryLevel`: int, 0..100
   - `freeStorageBytes` & `totalStorageBytes`: int, >= 0

## 2. The "Dirty Dozen" Adversarial Payloads
1. **Unauthenticated Read/Write**: `auth = null` attempting `get` or `set` on `/users/alice_123/home_node/main`.
2. **Cross-User Read**: `bob_456` attempting `get` on `/users/alice_123/home_node/main`.
3. **Cross-User Write (Node Hijacking)**: `bob_456` attempting to overwrite `ipAddress` on `/users/alice_123/home_node/main`.
4. **Identity Spoofing on Create**: `alice_123` writing `{"userId": "bob_456", ...}` inside `/users/alice_123/home_node/main`.
5. **Secret Token Cloud Leak Attempt**: `alice_123` attempting to write `"secretToken"` into `/users/alice_123/home_node/main` or `/users/alice_123/signaling/main`.
6. **Shadow Field Injection**: `alice_123` adding `"isAdmin": true` or `"ghostField": "hack"` on create or update.
7. **Immutable Field Tampering**: `alice_123` modifying `userId` during an `update`.
8. **Port Out-of-Range Poisoning**: Setting `port: 80` (privileged) or `port: 99999`.
9. **Battery Out-of-Range Poisoning**: Setting `batteryLevel: -5` or `batteryLevel: 150`.
10. **Denial-of-Wallet Oversized String**: Injecting a 10KB string into `deviceName`.
11. **Type Confusion Attack**: Passing a string `"8080"` for `port` or `"true"` for `isOnline`.
12. **Unfiltered Collection List Scraping**: Querying `users/alice_123/home_node` without matching `userId`.
