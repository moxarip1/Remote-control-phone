const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function validNodePayload(uid, overrides = {}) {
  const now = new Date();
  return {
    userId: uid,
    deviceName: "Galaxy S21 Home Server",
    ipAddress: "192.168.1.45",
    port: 8080,
    isOnline: true,
    batteryLevel: 88,
    freeStorageBytes: 32000000000,
    totalStorageBytes: 64000000000,
    lastUpdated: Date.now(),
    createdAt: now,
    updatedAt: now,
    ...overrides,
  };
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user cannot read or write home_node", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("home_node").doc("main").get()
  );
  await assertFails(
    unauthDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("home_node")
      .doc("main")
      .set(validNodePayload(ALICE_UID))
  );
});

test("Authenticated owner can create, read, update, and delete their own home_node without secretToken", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const docRef = aliceDb.collection("users").doc(ALICE_UID).collection("home_node").doc("main");
  const initial = validNodePayload(ALICE_UID);

  await assertSucceeds(docRef.set(initial));
  await assertSucceeds(docRef.get());
  await assertSucceeds(
    docRef.update({
      isOnline: false,
      batteryLevel: 75,
      updatedAt: new Date(),
    })
  );
  await assertSucceeds(docRef.delete());
});

test("Cross-user access is strictly denied for read, update, and delete", async () => {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context
      .firestore()
      .collection("users")
      .doc(ALICE_UID)
      .collection("home_node")
      .doc("main")
      .set(validNodePayload(ALICE_UID));
  });

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const aliceDocFromBob = bobDb.collection("users").doc(ALICE_UID).collection("home_node").doc("main");

  await assertFails(aliceDocFromBob.get());
  await assertFails(aliceDocFromBob.update({ isOnline: false, updatedAt: new Date() }));
  await assertFails(aliceDocFromBob.delete());
});

test("Shadow update with ghost field, secretToken leak, or invalid port/battery fails validation", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const docRef = aliceDb.collection("users").doc(ALICE_UID).collection("home_node").doc("main");

  // Phase 1.6: Writing secretToken to Firestore is strictly forbidden
  await assertFails(docRef.set(validNodePayload(ALICE_UID, { secretToken: "leaked-token" })));

  // Ghost field on create
  await assertFails(docRef.set(validNodePayload(ALICE_UID, { isVerifiedAdmin: true })));

  // Out-of-bounds port
  await assertFails(docRef.set(validNodePayload(ALICE_UID, { port: 80 })));

  // Out-of-bounds batteryLevel
  await assertFails(docRef.set(validNodePayload(ALICE_UID, { batteryLevel: 150 })));
});
