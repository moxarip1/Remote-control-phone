# Security Specification (`security_spec.md`)

## 1. Data Invariants
1. **Strict Ownership Isolation**: Every `HomeNodeInfo` document at `/users/{userId}/home_node/{nodeId}` belongs exclusively to `userId`. Only an authenticated user with `request.auth.uid == userId` may read, create, update, or delete their home node state.
2. **Identity Integrity**: `incoming().userId` must strictly equal `request.auth.uid` and `userId` on creation, and remain immutable on updates (`incoming().userId == existing().userId`).
3. **Temporal Immutability & Bounds**: `createdAt` and `updatedAt` must be valid Firestore `timestamp` objects (`<= request.time`). On update, `createdAt` is strictly immutable (`incoming().createdAt == existing().createdAt`).
4. **Network & Hardware Telemetry Bounds**:
   - `deviceName`: string, 1..120 chars
   - `ipAddress`: string, 0..64 chars
   - `port`: int, 1024..65535
   - `secretToken`: string, 0..128 chars
   - `batteryLevel`: int, 0..100
   - `freeStorageBytes` & `totalStorageBytes`: int, >= 0

## 2. The "Dirty Dozen" Adversarial Payloads
1. **Unauthenticated Read/Write**: `auth = null` attempting `get` or `set` on `/users/alice_123/home_node/main`.
2. **Cross-User Read (PII/Token Leak)**: `bob_456` attempting `get` on `/users/alice_123/home_node/main` to steal `secretToken`.
3. **Cross-User Write (Node Hijacking)**: `bob_456` attempting to overwrite `ipAddress` on `/users/alice_123/home_node/main`.
4. **Identity Spoofing on Create**: `alice_123` writing `{"userId": "bob_456", ...}` inside `/users/alice_123/home_node/main`.
5. **Shadow Field Injection**: `alice_123` adding `"isAdmin": true` or `"ghostField": "hack"` on create or update.
6. **Immutable Field Tampering**: `alice_123` modifying `createdAt` or `userId` during an `update`.
7. **Port Out-of-Range Poisoning**: Setting `port: 80` (privileged) or `port: 99999`.
8. **Battery Out-of-Range Poisoning**: Setting `batteryLevel: -5` or `batteryLevel: 150`.
9. **Denial-of-Wallet Oversized String**: Injecting a 10KB string into `deviceName` or `secretToken`.
10. **Type Confusion Attack**: Passing a string `"8080"` for `port` or `"true"` for `isOnline`.
11. **Unfiltered Collection List Scraping**: Querying `users/alice_123/home_node` without matching `userId`.
12. **Future Timestamp Injection**: Passing a client timestamp set in the future (`> request.time`).
