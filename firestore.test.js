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

function pastTimestamp() {
  return new Date(Date.now() - 1000);
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

test("Unauthenticated user: cannot list or write chats", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection(`users/${ALICE_UID}/chats`).get());
  const ts = pastTimestamp();
  await assertFails(
    unauthDb.collection(`users/${ALICE_UID}/chats`).doc("chat_1").set({
      userId: ALICE_UID,
      title: "Hello",
      languageHint: "auto",
      lastMessagePreview: "",
      messageCount: 0,
      createdAt: ts,
      updatedAt: ts,
    })
  );
});

test("Authenticated user: can create, read, update, and delete own chat", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const chatRef = aliceDb.collection(`users/${ALICE_UID}/chats`).doc("chat_1");
  const ts = pastTimestamp();

  await assertSucceeds(
    chatRef.set({
      userId: ALICE_UID,
      title: "Hindi Greeting",
      languageHint: "hi",
      lastMessagePreview: "नमस्ते!",
      messageCount: 1,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  await assertSucceeds(chatRef.get());
  await assertSucceeds(aliceDb.collection(`users/${ALICE_UID}/chats`).get());

  await assertSucceeds(
    chatRef.update({
      lastMessagePreview: "कैसे हैं आप?",
      messageCount: 2,
      updatedAt: pastTimestamp(),
    })
  );

  await assertSucceeds(chatRef.delete());
});

test("Authenticated user: cannot read or write another user's chat", async () => {
  const ts = pastTimestamp();
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.firestore().collection(`users/${BOB_UID}/chats`).doc("bob_chat").set({
      userId: BOB_UID,
      title: "Bob Secret",
      languageHint: "en",
      lastMessagePreview: "Hi",
      messageCount: 1,
      createdAt: ts,
      updatedAt: ts,
    });
  });

  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertFails(aliceDb.collection(`users/${BOB_UID}/chats`).get());
  await assertFails(aliceDb.collection(`users/${BOB_UID}/chats`).doc("bob_chat").get());
  await assertFails(
    aliceDb.collection(`users/${BOB_UID}/chats`).doc("alice_inject").set({
      userId: BOB_UID,
      title: "Injected",
      languageHint: "en",
      lastMessagePreview: "",
      messageCount: 0,
      createdAt: ts,
      updatedAt: ts,
    })
  );
});

test("Shadow update & invalid field tests fail on chat", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const chatRef = aliceDb.collection(`users/${ALICE_UID}/chats`).doc("chat_shadow");
  const ts = pastTimestamp();

  await assertFails(
    chatRef.set({
      userId: ALICE_UID,
      title: "Shadow",
      languageHint: "auto",
      lastMessagePreview: "",
      messageCount: 0,
      isAdmin: true,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  await assertFails(
    chatRef.set({
      userId: ALICE_UID,
      title: "Bad Lang",
      languageHint: "fr",
      lastMessagePreview: "",
      messageCount: 0,
      createdAt: ts,
      updatedAt: ts,
    })
  );
});

test("Messages: atomic batch create with parent chat succeeds, orphan message fails, update fails", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const chatRef = aliceDb.collection(`users/${ALICE_UID}/chats`).doc("chat_atomic");
  const msgRef = chatRef.collection("messages").doc("msg_1");
  const ts = pastTimestamp();

  // Orphan message without parent chat fails
  await assertFails(
    aliceDb.collection(`users/${ALICE_UID}/chats/nonexistent_chat/messages`).doc("msg_orphan").set({
      userId: ALICE_UID,
      chatId: "nonexistent_chat",
      role: "user",
      text: "Orphan",
      createdAt: ts,
    })
  );

  // Atomic batch create succeeds
  const batch = aliceDb.batch();
  batch.set(chatRef, {
    userId: ALICE_UID,
    title: "Atomic Chat",
    languageHint: "auto",
    lastMessagePreview: "Hello Abhinav AI",
    messageCount: 1,
    createdAt: ts,
    updatedAt: ts,
  });
  batch.set(msgRef, {
    userId: ALICE_UID,
    chatId: "chat_atomic",
    role: "user",
    text: "Hello Abhinav AI",
    createdAt: ts,
  });
  await assertSucceeds(batch.commit());

  // Message update is immutable (fails)
  await assertFails(msgRef.update({ text: "Tampered" }));

  // Message delete succeeds for owner (used in Clear Chat)
  await assertSucceeds(msgRef.delete());
});
