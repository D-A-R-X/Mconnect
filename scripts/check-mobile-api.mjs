#!/usr/bin/env node

import { readFile } from "node:fs/promises";
import { randomUUID } from "node:crypto";

const DEFAULT_BASE_URL = "https://api-mfpl.theairix.com/";
const DEFAULT_STORAGE_BASE_URL = "https://mg.theairix.com/";
const WRITE_CONFIRMATION = "I_UNDERSTAND_THIS_MUTATES_A_TEST_CP";
const SECRET_KEYS = /token|authorization|password|otp|phone/i;

function parseArgs(argv) {
  const result = { _: [] };
  for (let index = 0; index < argv.length; index += 1) {
    const value = argv[index];
    if (!value.startsWith("--")) {
      result._.push(value);
      continue;
    }
    const key = value.slice(2);
    if (["allow-write", "help", "verbose"].includes(key)) {
      result[key] = true;
      continue;
    }
    result[key] = argv[index + 1];
    index += 1;
  }
  return result;
}

function redact(value) {
  if (Array.isArray(value)) return value.map(redact);
  if (value && typeof value === "object") {
    return Object.fromEntries(
      Object.entries(value).map(([key, child]) => [
        key,
        SECRET_KEYS.test(key) ? "[REDACTED]" : redact(child),
      ]),
    );
  }
  return value;
}

function requireEnv(name) {
  const value = process.env[name]?.trim();
  if (!value) throw new Error(`Missing required environment variable: ${name}`);
  return value;
}

function asNumber(name) {
  const value = Number(requireEnv(name));
  if (!Number.isFinite(value)) throw new Error(`${name} must be a number`);
  return value;
}

function normalizedStatus(value) {
  return String(value ?? "").trim().toLowerCase().replaceAll("-", "_");
}

const args = parseArgs(process.argv.slice(2));
const command = args._[0] ?? "contracts";
const baseUrl = new URL(args["base-url"] ?? process.env.MCONNECT_BASE_URL ?? DEFAULT_BASE_URL);
const results = [];

async function request(method, path, options = {}) {
  const requestBaseUrl = options.baseUrl ? new URL(options.baseUrl) : baseUrl;
  const url = new URL(path.replace(/^\//, ""), requestBaseUrl);
  const headers = { Accept: "application/json", ...options.headers };
  if (options.token) headers.Authorization = `Bearer ${options.token}`;
  if (options.body !== undefined) headers["Content-Type"] = "application/json";

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), Number(args.timeout ?? 20_000));
  const startedAt = performance.now();
  let response;
  try {
    response = await fetch(url, {
      method,
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      signal: controller.signal,
    });
  } finally {
    clearTimeout(timer);
  }

  const text = await response.text();
  let data = null;
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = text.slice(0, 500);
    }
  }
  const result = {
    method,
    path: `${url.pathname}${url.search}`,
    status: response.status,
    durationMs: Math.round(performance.now() - startedAt),
    data,
  };
  results.push(result);
  console.log(`${response.ok ? "PASS" : "HTTP"} ${method} ${result.path} -> ${response.status} (${result.durationMs} ms)`);
  if (args.verbose) console.log(JSON.stringify(redact(data), null, 2));
  return result;
}

function assert(condition, message) {
  if (!condition) throw new Error(message);
  console.log(`PASS invariant: ${message}`);
}

function expectStatus(result, expected, requireStructuredSuccess = false) {
  const accepted = Array.isArray(expected) ? expected : [expected];
  assert(accepted.includes(result.status), `${result.method} ${result.path} returned ${accepted.join(" or ")}`);
  if (requireStructuredSuccess) {
    assert(typeof result.data === "object" && result.data !== null, `${result.method} ${result.path} returned JSON`);
    assert("success" in result.data, `${result.method} ${result.path} returned structured JSON`);
  }
}

async function contracts() {
  const routes = [
    ["GET", "/api/marketing/clientPlaceVisits/joint-workflow?id=contract-probe"],
    ["POST", "/api/marketing/clientPlaceVisits/joint-arrival-preflight"],
    ["POST", "/api/geotrack/visit/arrival-otp/request"],
    ["POST", "/api/geotrack/visit/arrival-otp/verify"],
    ["POST", "/api/marketing/clientPlaceVisits/markClientMet"],
    ["POST", "/api/marketing/clientPlaceVisits/setOutcome"],
    ["POST", "/api/marketing/clientPlaceVisits/joint-submit-review"],
    ["POST", "/api/marketing/clientPlaceVisits/joint-complete-review"],
    ["GET", "/api/marketing/clientPlaceVisits/my?scope=mine&status=completed&pageSize=1"],
  ];
  for (const [method, path] of routes) {
    const result = await request(method, path, { body: method === "POST" ? {} : undefined });
    expectStatus(result, 401, true);
  }
}

async function storageContracts() {
  const storageBaseUrl = args["storage-base-url"]
    ?? process.env.MFPL_API_BASE_URL
    ?? DEFAULT_STORAGE_BASE_URL;
  const routes = [
    ["POST", "/api/storage/uploads", {}],
    ["POST", "/api/storage/uploads/contract-probe/complete", { storageId: "contract-probe" }],
    ["DELETE", "/api/storage/uploads/contract-probe", undefined],
    ["GET", "/api/storage/files/contract-probe", undefined],
  ];
  for (const [method, path, body] of routes) {
    const result = await request(method, path, { baseUrl: storageBaseUrl, body });
    expectStatus(result, 401, true);
  }
  console.log("INFO compatibility upload is intentionally not POST-probed because that would create a file.");
}

async function authRecoveryContracts() {
  const requestResult = await request(
    "POST",
    "/api/auth/device-binding/recovery/request",
    { body: {} },
  );
  expectStatus(requestResult, 400, true);
  assert(
    typeof requestResult.data?.error === "string"
      && requestResult.data.error.includes("employeeId")
      && requestResult.data.error.includes("deviceId"),
    "recovery request validates credential and device fields",
  );

  const confirmResult = await request(
    "POST",
    "/api/auth/device-binding/recovery/confirm",
    { body: {} },
  );
  expectStatus(confirmResult, 400, true);
  assert(
    confirmResult.data?.code === "DEVICE_RECOVERY_FAILED",
    "recovery confirmation returns the stable generic failure code",
  );

  const verifiedOtpConfirmResult = await request(
    "POST",
    "/api/auth/device-binding/recovery/confirm-verified-otp",
    { body: {} },
  );
  expectStatus(verifiedOtpConfirmResult, 400, true);
  assert(
    verifiedOtpConfirmResult.data?.code === "DEVICE_RECOVERY_FAILED",
    "verified-OTP recovery confirmation returns the stable generic failure code",
  );
}

function workflowFrom(result, who) {
  assert(result.status === 200, `${who} workflow request succeeds`);
  assert(result.data?.success === true && result.data?.workflow, `${who} workflow response has workflow data`);
  return result.data.workflow;
}

async function getWorkflow(token, cpId, who) {
  return workflowFrom(
    await request("GET", `/api/marketing/clientPlaceVisits/joint-workflow?id=${encodeURIComponent(cpId)}`, { token }),
    who,
  );
}

async function completedList(token) {
  const result = await request(
    "GET",
    "/api/marketing/clientPlaceVisits/my?scope=mine&status=completed&pageSize=100",
    { token },
  );
  assert(result.status === 200 && result.data?.success === true, "completed CP list request succeeds");
  assert(Array.isArray(result.data.visits), "completed CP list contains visits array");
  return { total: Number(result.data.total ?? result.data.visits.length), visits: result.data.visits };
}

function containsCompletedVisit(list, cpId) {
  return list.visits.some((visit) =>
    String(visit.id ?? visit._id ?? visit.visitId) === cpId && normalizedStatus(visit.status) === "completed",
  );
}

async function jointReadAudit() {
  const cpId = requireEnv("MCONNECT_JOINT_CP_ID");
  const lowToken = requireEnv("MCONNECT_LOW_TOKEN");
  const seniorToken = requireEnv("MCONNECT_SENIOR_TOKEN");
  const [low, senior] = await Promise.all([
    getWorkflow(lowToken, cpId, "lower-level participant"),
    getWorkflow(seniorToken, cpId, "senior participant"),
  ]);
  assert(low.actorRole === "outcome_owner", "lower-level participant is the outcome_owner");
  assert(senior.actorRole === "reviewer", "senior participant is the reviewer");
  assert(low.outcomeOwnerStaffId === senior.outcomeOwnerStaffId, "both views agree on outcome owner");
  assert(low.reviewerStaffId === senior.reviewerStaffId, "both views agree on reviewer");
  assert(low.outcomeOwnerStaffId !== low.reviewerStaffId, "outcome owner and reviewer are different staff");

  if (normalizedStatus(low.state) === "completed") {
    const [lowList, seniorList] = await Promise.all([completedList(lowToken), completedList(seniorToken)]);
    assert(containsCompletedVisit(lowList, cpId), "completed CP is visible to lower-level participant");
    assert(containsCompletedVisit(seniorList, cpId), "completed CP is visible to senior participant");
  } else {
    console.log(`INFO workflow state is ${low.state}; completed-list visibility is checked after final review.`);
  }
}

function requireWriteUnlock() {
  assert(args["allow-write"] === true, "full flow was explicitly enabled with --allow-write");
  assert(process.env.MCONNECT_CONFIRM_WRITES === WRITE_CONFIRMATION, `MCONNECT_CONFIRM_WRITES equals ${WRITE_CONFIRMATION}`);
  assert(process.env.MCONNECT_TEST_FIXTURE === "true", "MCONNECT_TEST_FIXTURE=true confirms disposable test data");
}

async function successfulPost(path, token, body, idempotent = false) {
  const headers = idempotent ? { "Idempotency-Key": randomUUID() } : undefined;
  const result = await request("POST", path, { token, body, headers });
  assert(result.status >= 200 && result.status < 300, `${path} returns HTTP success`);
  assert(result.data?.success === true, `${path} returns success=true`);
  return result.data;
}

async function jointFullFlow() {
  requireWriteUnlock();
  const cpId = requireEnv("MCONNECT_JOINT_CP_ID");
  const fieldVisitId = requireEnv("MCONNECT_LOW_FIELD_VISIT_ID");
  const lowToken = requireEnv("MCONNECT_LOW_TOKEN");
  const seniorToken = requireEnv("MCONNECT_SENIOR_TOKEN");
  const lat = asNumber("MCONNECT_LAT");
  const lng = asNumber("MCONNECT_LNG");
  const accuracyMeters = Number(process.env.MCONNECT_ACCURACY_METERS ?? 10);
  const outcome = process.env.MCONNECT_OUTCOME?.trim() || "follow_up";
  const notes = requireEnv("MCONNECT_OUTCOME_NOTES");
  const arrivalPhotoStorageId = requireEnv("MCONNECT_ARRIVAL_PHOTO_STORAGE_ID");
  const reviewerRemark = requireEnv("MCONNECT_REVIEWER_REMARK");

  const initialLow = await getWorkflow(lowToken, cpId, "lower-level participant");
  const initialSenior = await getWorkflow(seniorToken, cpId, "senior participant");
  assert(initialLow.actorRole === "outcome_owner", "only lower-level participant owns OTP and outcome");
  assert(initialSenior.actorRole === "reviewer", "only senior participant owns final review");
  assert(normalizedStatus(initialLow.state) !== "completed", "fixture is not already completed");
  const [beforeLow, beforeSenior] = await Promise.all([completedList(lowToken), completedList(seniorToken)]);

  const preflight = await successfulPost(
    "/api/marketing/clientPlaceVisits/joint-arrival-preflight",
    lowToken,
    { id: cpId, fieldVisitId, lat, lng, accuracyMeters, capturedAt: Date.now() },
  );
  assert(preflight.workflow?.isWithinCompletionRadius === true, "server confirms both staff are strictly within 50 metres");
  const seniorOtpAttempt = await request(
    "POST",
    "/api/geotrack/visit/arrival-otp/request",
    { token: seniorToken, body: { visitId: fieldVisitId, lat, lng } },
  );
  assert(seniorOtpAttempt.status === 403, "higher-level reviewer cannot request the client OTP");
  const otpRequest = await successfulPost(
    "/api/geotrack/visit/arrival-otp/request",
    lowToken,
    { visitId: fieldVisitId, lat, lng },
  );
  assert(Boolean(otpRequest.contactPhoneMasked), "OTP request response identifies a masked client phone");
  console.log("INFO OTP request was accepted by the API. SMS handset delivery still requires provider receipt or client confirmation.");

  await successfulPost(
    "/api/geotrack/visit/arrival-otp/verify",
    lowToken,
    {
      visitId: fieldVisitId,
      otp: requireEnv("MCONNECT_TEST_OTP"),
      lat,
      lng,
      arrivalPhotoStorageId,
    },
  );
  await successfulPost(
    "/api/marketing/clientPlaceVisits/markClientMet",
    lowToken,
    { id: cpId, clientMet: true },
  );
  await successfulPost(
    "/api/marketing/clientPlaceVisits/setOutcome",
    lowToken,
    { id: cpId, outcome, notes },
  );
  const draft = await getWorkflow(lowToken, cpId, "lower-level participant after outcome");
  const submitted = await successfulPost(
    "/api/marketing/clientPlaceVisits/joint-submit-review",
    lowToken,
    {
      id: cpId,
      fieldVisitId,
      lat,
      lng,
      accuracyMeters,
      capturedAt: Date.now(),
      arrivalPhotoStorageId,
      expectedOutcomeRevision: draft.outcomeRevision,
    },
    true,
  );
  assert(normalizedStatus(submitted.workflow?.state) === "pending_review", "lower-level outcome moves Joint CP to pending_review");

  const review = await getWorkflow(seniorToken, cpId, "senior participant after submission");
  assert(review.actorRole === "reviewer" && review.canReview === true, "senior can review the submitted outcome");
  assert(Number.isFinite(Number(review.outcomeRevision)), "review response contains an outcome revision");
  const completed = await successfulPost(
    "/api/marketing/clientPlaceVisits/joint-complete-review",
    seniorToken,
    {
      id: cpId,
      expectedOutcomeRevision: Number(review.outcomeRevision),
      reviewerRemark,
    },
    true,
  );
  assert(normalizedStatus(completed.workflow?.state) === "completed", "senior review completes the Joint CP");

  const [afterLow, afterSenior] = await Promise.all([completedList(lowToken), completedList(seniorToken)]);
  assert(containsCompletedVisit(afterLow, cpId), "completed CP appears for lower-level participant");
  assert(containsCompletedVisit(afterSenior, cpId), "completed CP appears for senior participant");
  assert(afterLow.total >= beforeLow.total + 1, "lower-level participant completed count increases");
  assert(afterSenior.total >= beforeSenior.total + 1, "senior participant completed count increases");
}

async function customRequest() {
  const method = String(args.method ?? "GET").toUpperCase();
  const path = args.path;
  if (!path) throw new Error("custom request requires --path");
  let body;
  if (args["body-file"]) body = JSON.parse(await readFile(args["body-file"], "utf8"));
  const token = args["token-env"] ? requireEnv(args["token-env"]) : process.env.MCONNECT_TOKEN?.trim();
  if (!["GET", "HEAD", "OPTIONS"].includes(method) && token) requireWriteUnlock();
  const result = await request(method, path, { token, body });
  if (args["expect-status"]) expectStatus(result, Number(args["expect-status"]));
}

function printHelp() {
  console.log(`Usage:
  node scripts/check-mobile-api.mjs contracts
  node scripts/check-mobile-api.mjs storage-contracts [--storage-base-url https://mg.theairix.com/]
  node scripts/check-mobile-api.mjs auth-recovery-contracts
  node scripts/check-mobile-api.mjs joint-read
  node scripts/check-mobile-api.mjs joint-flow --allow-write
  node scripts/check-mobile-api.mjs request --method GET --path /api/health [--token-env MCONNECT_TOKEN]

Safe defaults:
  contracts performs unauthenticated route/auth-contract probes only.
  storage-contracts verifies preferred storage routes reject unauthenticated calls and never uploads bytes.
  auth-recovery-contracts validates the two pre-login routes using empty, non-mutating bodies.
  joint-read requires MCONNECT_JOINT_CP_ID, MCONNECT_LOW_TOKEN and MCONNECT_SENIOR_TOKEN.

Full disposable-test flow additionally requires:
  MCONNECT_CONFIRM_WRITES=${WRITE_CONFIRMATION}
  MCONNECT_TEST_FIXTURE=true
  MCONNECT_LOW_FIELD_VISIT_ID, MCONNECT_LAT, MCONNECT_LNG, MCONNECT_TEST_OTP
  MCONNECT_OUTCOME_NOTES, MCONNECT_ARRIVAL_PHOTO_STORAGE_ID,
  MCONNECT_REVIEWER_REMARK and optionally MCONNECT_OUTCOME / MCONNECT_ACCURACY_METERS

Tokens and OTP values are never printed. Set --verbose to print redacted response JSON.`);
}

try {
  if (args.help) printHelp();
  else if (command === "contracts") await contracts();
  else if (command === "storage-contracts") await storageContracts();
  else if (command === "auth-recovery-contracts") await authRecoveryContracts();
  else if (command === "joint-read") await jointReadAudit();
  else if (command === "joint-flow") await jointFullFlow();
  else if (command === "request") await customRequest();
  else throw new Error(`Unknown command: ${command}`);
  if (!args.help) console.log(`\nPASS ${command}: ${results.length} request(s) completed.`);
} catch (error) {
  console.error(`\nFAIL ${command}: ${error instanceof Error ? error.message : String(error)}`);
  process.exitCode = 1;
}
