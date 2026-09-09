#!/usr/bin/env node

import { readFile } from "node:fs/promises";
import { randomUUID } from "node:crypto";

const DEFAULT_BASE_URL = "https://api-mfpl.theairix.com/";
const DEFAULT_STORAGE_BASE_URL = "https://mg.theairix.com/";
const DEFAULT_GEO_BASE_URL = "https://api-geo.theairix.com/";
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
const baseUrl = new URL(
  args["base-url"]
    ?? process.env.MCONNECT_BASE_URL
    ?? process.env.MOBILE_API_BASE_URL
    ?? DEFAULT_BASE_URL,
);
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
      redirect: options.redirect ?? "follow",
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
    location: response.headers.get("location"),
    contentType: response.headers.get("content-type"),
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
  assert(
    accepted.includes(result.status),
    `${result.method} ${result.path} expected ${accepted.join(" or ")}, got ${result.status}`,
  );
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
    ["POST", "/api/geotrack/visit/complete"],
    ["GET", "/api/marketing/clientPlaceVisits/my?scope=mine&status=completed&pageSize=1"],
  ];
  for (const [method, path] of routes) {
    const result = await request(method, path, { body: method === "POST" ? {} : undefined });
    expectStatus(result, 401, true);
  }
}

async function cpSvContracts() {
  const storageBaseUrl = args["storage-base-url"]
    ?? process.env.MFPL_API_BASE_URL
    ?? DEFAULT_STORAGE_BASE_URL;
  const geoBaseUrl = args["geo-base-url"]
    ?? process.env.MCONNECT_GEO_BASE_URL
    ?? DEFAULT_GEO_BASE_URL;
  const failures = [];
  const businessRoutes = [
    // Nested form dependencies: staff/template levels, projects, clients,
    // leads and address/route resolution used before a mutation is enabled.
    ["GET", "/api/hr/staff?status=active"],
    ["GET", "/api/hr/staff/get?id=contract-probe"],
    ["GET", "/api/projects/get?id=contract-probe"],
    ["GET", "/api/telecaller/leads/search-by-phone?phone=1000000000"],
    ["GET", "/api/clients/search-by-phone?phone=1000000000"],
    ["GET", "/api/clients/referral-candidates?query=contract-probe&limit=1"],
    ["POST", "/api/telecaller/leads/update"],
    ["POST", "/api/address/parse"],
    ["GET", "/api/address/autocomplete?q=contract-probe"],
    ["GET", "/api/address/place?placeId=contract-probe"],
    ["POST", "/api/geotrack/geocode-address"],
    ["POST", "/api/geotrack/route", [400, 401]],
    ["GET", "/api/hr/attendance/today"],
    ["GET", "/api/hr/attendance/day-sessions"],
    ["GET", "/api/hr/attendance/my?fromDate=2099-01-01&toDate=2099-01-01"],
    ["POST", "/api/hr/attendance/punch-in"],
    ["POST", "/api/hr/attendance/punch-out"],
    ["GET", "/api/hr/permissions/monthly-usage"],
    ["GET", "/api/mobile/dashboard?date=2099-01-01"],

    // Tracking, lists and trip lifecycle.
    ["GET", "/api/tracking/bootstrap?deviceId=contract-probe"],
    ["POST", "/api/tracking/device/sync", [400, 401]],
    ["POST", "/api/tracking/consent", [400, 401]],
    ["POST", "/api/geotrack/tamper/report", [400, 401]],
    ["POST", "/api/geotrack/consent", [400, 401]],
    ["GET", "/api/geotrack/consent/status"],
    ["GET", "/api/geotrack/assigned-places"],
    ["GET", "/api/geotrack/today-visits?date=2099-01-01"],
    ["GET", "/api/sitevisits/my?pageSize=1"],
    ["POST", "/api/geotrack/visit/create"],
    ["POST", "/api/geotrack/visit/start"],
    ["POST", "/api/geotrack/visit/complete"],
    ["GET", "/api/geotrack/timeline?dayStart=0&dayEnd=1", [400, 401]],
    ["GET", "/api/geotrack/session-route?dayStart=0&dayEnd=1", [400, 401]],

    // Driver-backed SV trip actions reachable from the shared trip screen.
    ["GET", "/api/mms-fleet/driver/trips"],
    ["POST", "/api/mms-fleet/driver/arrive"],
    ["POST", "/api/mms-fleet/driver/start"],
    ["POST", "/api/mms-fleet/driver/on-site"],
    ["POST", "/api/mms-fleet/driver/picked-from-site"],
    ["POST", "/api/mms-fleet/driver/end"],

    // Arrival proof and OTP.
    ["POST", "/api/geotrack/visit/arrival-otp/request"],
    ["POST", "/api/geotrack/visit/arrival-otp/verify"],
    ["POST", "/api/geotrack/visit/arrival-otp/cancel"],

    // CP create, detail, outcome and synchronization.
    ["POST", "/api/marketing/clientPlaceVisits/create"],
    ["GET", "/api/marketing/clientPlaceVisits/get?id=contract-probe"],
    ["GET", "/api/marketing/clientPlaceVisits/my?scope=mine&pageSize=1"],
    ["GET", "/api/marketing/clientPlaceVisits/completed-count?date=2099-01-01"],
    ["GET", "/api/marketing/clientPlaceVisits/completion-health?fromDate=2099-01-01&toDate=2099-01-01"],
    ["POST", "/api/marketing/clientPlaceVisits/completion-repair/preview"],
    ["POST", "/api/marketing/clientPlaceVisits/completion-repair/apply"],
    ["GET", "/api/marketing/clientPlaceVisits/filter-options?scope=mine"],
    ["POST", "/api/marketing/clientPlaceVisits/markClientMet"],
    ["POST", "/api/marketing/clientPlaceVisits/setOutcome"],
    ["POST", "/api/marketing/clientPlaceVisits/referral"],
    ["POST", "/api/marketing/clientPlaceVisits/cancel"],
    ["POST", "/api/marketing/clientPlaceVisits/convertToSiteVisit"],
    ["POST", "/api/marketing/cp-visits/geofence-remark"],
    ["POST", "/api/marketing/cp-visits/otp-assist"],
    ["GET", "/api/marketing/cp-visits/pending-approvals"],
    ["GET", "/api/marketing/cp-visits/approval-route?id=contract-probe"],
    ["POST", "/api/marketing/cp-visits/approve"],
    ["POST", "/api/marketing/cp-visits/reject"],

    // Joint CP owner/reviewer contract.
    ["GET", "/api/marketing/clientPlaceVisits/joint-workflow?id=contract-probe"],
    ["POST", "/api/marketing/clientPlaceVisits/joint-arrival-preflight"],
    ["POST", "/api/marketing/clientPlaceVisits/joint-participant-ready"],
    ["POST", "/api/marketing/clientPlaceVisits/joint-submit-review"],
    ["POST", "/api/marketing/clientPlaceVisits/joint-complete-review"],

    // Site-visit confirmation, QR, outcome and lifecycle.
    ["POST", "/api/marketing/siteVisits/create"],
    ["POST", "/api/marketing/siteVisits/scanQr"],
    ["POST", "/api/marketing/siteVisits/markOnCounselling"],
    ["POST", "/api/marketing/siteVisits/setOutcome"],
    ["POST", "/api/marketing/siteVisits/postpone"],
    ["POST", "/api/marketing/siteVisits/cancel"],
    ["POST", "/api/marketing/siteVisits/convertToBooking"],
    ["POST", "/api/marketing/siteVisits/markPickedUp"],
    ["POST", "/api/marketing/siteVisits/markClientStarted"],
    ["POST", "/api/marketing/siteVisits/markArrivedSite"],
    ["POST", "/api/marketing/siteVisits/markPickedFromSite"],
    ["POST", "/api/marketing/siteVisits/markDropped"],
    ["GET", "/api/sitevisits/filter-options"],

    // Booking form dependencies and create/draft persistence.
    ["GET", "/api/marketing/projects"],
    ["GET", "/api/marketing/inventory-units?projectId=contract-probe"],
    ["GET", "/api/marketing/inventory-units/layout?projectId=contract-probe"],
    ["GET", "/api/bookings/plot-prefill?plotId=contract-probe"],
    ["GET", "/api/bookings/conversion-prefill?mobileNumber=1000000000"],
    ["GET", "/api/bookings/exchange-source-candidates?mobileNumber=1000000000"],
    ["POST", "/api/bookings"],
    ["POST", "/api/bookings/draft/save"],
    ["GET", "/api/bookings/draft/get?sourceKey=contract-probe"],
    ["POST", "/api/bookings/draft/clear"],
    ["GET", "/api/marketing/bookings/my?pageSize=1"],
    ["GET", "/api/bookings/contract-probe"],
    ["PATCH", "/api/bookings/contract-probe"],
    ["POST", "/api/bookings/contract-probe/approve"],
    ["POST", "/api/bookings/contract-probe/reject"],

    // Collection CP lookup, submit and list synchronization.
    ["GET", "/api/postsales/cases/byMobile?mobile=1000000000"],
    ["GET", "/api/postsales/cases/list"],
    ["POST", "/api/postsales/collections/submit"],
    ["GET", "/api/postsales/collections/my?pageSize=1"],
    ["POST", "/api/postsales/collections/correct"],
    ["GET", "/api/postsales/collections/for-accounts"],
    ["POST", "/api/postsales/collections/approve"],
    ["POST", "/api/postsales/collections/reject"],

    // Compatibility upload used when the preferred storage service is absent.
    ["POST", "/api/storage/upload"],
  ];
  for (const [method, path, expectedStatus = 401] of businessRoutes) {
    try {
      const result = await request(method, path, { body: method === "GET" ? undefined : {} });
      expectStatus(result, expectedStatus, true);
    } catch (error) {
      failures.push(`${method} ${path}: ${error.message}`);
      console.error(`FAIL contract: ${method} ${path}: ${error.message}`);
    }
  }

  const externalRoutes = [
    // The direct GeoTrack service validates its request body before auth, so
    // an empty non-mutating probe may return 400 or 401 depending on route.
    [geoBaseUrl, "POST", "/api/tracking/location/batch", [400, 401]],
    [geoBaseUrl, "POST", "/api/tracking/heartbeat", [400, 401]],
    [geoBaseUrl, "POST", "/api/tracking/tamper-events", [400, 401]],
    [geoBaseUrl, "POST", "/api/geotrack/start", [400, 401]],
    [geoBaseUrl, "POST", "/api/geotrack/stop", [400, 401]],
    [storageBaseUrl, "POST", "/api/storage/uploads", 401],
    [storageBaseUrl, "POST", "/api/storage/uploads/contract-probe/complete", 401],
    [storageBaseUrl, "DELETE", "/api/storage/uploads/contract-probe", 401],
    [storageBaseUrl, "GET", "/api/storage/files/contract-probe", 404],
  ];
  for (const [routeBaseUrl, method, path, expectedStatus] of externalRoutes) {
    try {
      const result = await request(method, path, {
        baseUrl: routeBaseUrl,
        body: method === "GET" || method === "DELETE" ? undefined : {},
      });
      expectStatus(result, expectedStatus, method !== "GET" || path !== "/api/storage/files/contract-probe");
    } catch (error) {
      failures.push(`${method} ${path}: ${error.message}`);
      console.error(`FAIL contract: ${method} ${path}: ${error.message}`);
    }
  }
  const total = businessRoutes.length + externalRoutes.length;
  if (failures.length > 0) {
    throw new Error(
      `${total - failures.length}/${total} CP/SV module endpoint contracts passed; failures:\n- ${failures.join("\n- ")}`,
    );
  }
  console.log(`PASS all ${total} CP/SV module endpoint contracts passed`);
}

async function geoTrackDirectContracts() {
  const geoBaseUrl = args["geo-base-url"]
    ?? process.env.MCONNECT_GEO_BASE_URL
    ?? DEFAULT_GEO_BASE_URL;
  const live = await request("GET", "/api/tracking/live?limit=1", {
    baseUrl: geoBaseUrl,
  });
  expectStatus(live, 200, true);
  assert(Array.isArray(live.data?.data), "preferred live route returns a data array");

  const liveAlias = await request("GET", "/api/geotrack/live-status?limit=1", {
    baseUrl: geoBaseUrl,
  });
  expectStatus(liveAlias, 200, true);
  assert(Array.isArray(liveAlias.data?.data), "web live-status alias returns a data array");

  const invalidToken = "invalid-geotrack-contract-probe";
  const headers = { "Idempotency-Key": "invalid-geotrack-contract-probe" };
  const writeRoutes = [
    ["/api/tracking/location/batch", { deviceId: "contract-probe", requestId: "contract-probe", points: [] }],
    ["/api/tracking/heartbeat", {
      deviceId: "contract-probe",
      requestId: "contract-probe",
      deviceSequence: 1,
      batteryPct: 50,
      appVersion: "contract-probe",
      recordedAt: 1,
    }],
    ["/api/tracking/tamper-events", {
      eventType: "NETWORK_ONLINE",
      detectedAt: 1,
      requestId: "contract-probe",
      metadata: { source: "contract-probe" },
    }],
    ["/api/geotrack/start", {}],
    ["/api/geotrack/stop", {}],
  ];
  const failures = [];
  for (const [path, body] of writeRoutes) {
    const result = await request("POST", path, {
      baseUrl: geoBaseUrl,
      token: invalidToken,
      headers,
      body,
    });
    try {
      expectStatus(result, 401, true);
    } catch (error) {
      failures.push(error instanceof Error ? error.message : String(error));
    }
  }
  assert(failures.length === 0, failures.join("; "));
}

async function storageContracts() {
  const storageBaseUrl = args["storage-base-url"]
    ?? process.env.MFPL_API_BASE_URL
    ?? DEFAULT_STORAGE_BASE_URL;
  const protectedRoutes = [
    ["POST", "/api/storage/uploads", {}],
    ["POST", "/api/storage/uploads/contract-probe/complete", { storageId: "contract-probe" }],
    ["DELETE", "/api/storage/uploads/contract-probe", undefined],
  ];
  for (const [method, path, body] of protectedRoutes) {
    const result = await request(method, path, { baseUrl: storageBaseUrl, body });
    expectStatus(result, 401, true);
  }

  const readFixtureId = process.env.MCONNECT_STORAGE_READ_ID;
  if (readFixtureId) {
    const fixtureRead = await request(
      "GET",
      `/api/storage/files/${encodeURIComponent(readFixtureId)}`,
      { baseUrl: storageBaseUrl, redirect: "manual" },
    );
    expectStatus(fixtureRead, 307);
    assert(
      typeof fixtureRead.location === "string" && fixtureRead.location.length > 0,
      "storage read returns a redirect target",
    );
  } else {
    console.log("INFO set MCONNECT_STORAGE_READ_ID to verify a real legacy/external file redirect.");
  }

  const missingRead = await request("GET", "/api/storage/files/contract-probe", {
    baseUrl: storageBaseUrl,
    redirect: "manual",
  });
  expectStatus(missingRead, 404);
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

async function deviceLoginContracts() {
  const buildMetadata = {
    deviceType: "mobile",
    deviceId: "contract-probe",
    devicePlatform: "android",
    deviceModel: "Contract probe",
    appVersion: "1.0",
    appBuild: 72,
  };
  const routes = [
    ["/api/auth/send-otp", { phone: "", ...buildMetadata }],
    ["/api/auth/verify-otp", { phone: "", otp: "", ...buildMetadata }],
    ["/api/auth/login-with-employee-id", { employeeId: "", password: "", ...buildMetadata }],
  ];
  for (const [path, body] of routes) {
    const result = await request("POST", path, { body });
    assert(
      result.status === 400 || result.status === 401,
      `${path} rejects empty credentials with HTTP 400 or 401`,
    );
    assert(
      result.data && typeof result.data === "object",
      `${path} returned structured JSON`,
    );
  }
}

async function deviceRolloutContracts() {
  const mode = String(args["rollout-mode"] ?? "compatibility").toLowerCase();
  assert(
    mode === "compatibility" || mode === "enforced",
    "rollout mode is compatibility or enforced",
  );
  const platform = String(args.platform ?? "android").toLowerCase();
  const legacyBuild = Number(args["legacy-build"] ?? 71);
  const targetBuild = Number(args["target-build"] ?? 72);
  assert(Number.isInteger(legacyBuild) && legacyBuild > 0, "legacy build is a positive integer");
  assert(Number.isInteger(targetBuild) && targetBuild > legacyBuild, "target build is newer than legacy build");

  const versionResult = await request(
    "GET",
    `/api/mobile/app-version?platform=${encodeURIComponent(platform)}&currentVersion=1.0&buildNumber=${legacyBuild}`,
    {
      headers: {
        "X-App-Version": "1.0",
        "X-App-Build": String(legacyBuild),
      },
    },
  );
  expectStatus(versionResult, 200, true);
  const minimumBuild = Number(versionResult.data?.minimumSupportedBuildNumber);
  assert(Number.isInteger(minimumBuild), "app-version returns minimumSupportedBuildNumber");
  if (mode === "compatibility") {
    assert(minimumBuild <= legacyBuild, "compatibility mode still supports the legacy APK build");
  } else {
    assert(minimumBuild >= targetBuild, "enforced mode requires the target build or newer");
  }

  const buildMetadata = (appBuild) => ({
    deviceType: "mobile",
    deviceId: "contract-probe",
    devicePlatform: platform,
    deviceModel: "Contract probe",
    appVersion: "1.0",
    appBuild,
  });
  const authBodies = [
    // Values are non-empty so the HTTP wrapper cannot reject them before the
    // rollout gate, but deliberately invalid so no OTP/session can be created.
    ["/api/auth/send-otp", (build) => ({ phone: "1000000000", ...buildMetadata(build) })],
    ["/api/auth/verify-otp", (build) => ({ phone: "1000000000", otp: "000000", ...buildMetadata(build) })],
    ["/api/auth/login-with-employee-id", (build) => ({ employeeId: "contract-probe", password: "invalid-contract-probe", ...buildMetadata(build) })],
  ];
  for (const [path, bodyForBuild] of authBodies) {
    const legacyResult = await request("POST", path, { body: bodyForBuild(legacyBuild) });
    if (mode === "enforced") {
      expectStatus(legacyResult, 426, true);
      assert(legacyResult.data?.code === "UPDATE_REQUIRED", `${path} returns UPDATE_REQUIRED for the legacy build`);
      assert(Number(legacyResult.data?.minimumBuild) >= targetBuild, `${path} returns the enforced minimum build`);
    } else {
      expectStatus(legacyResult, [400, 401], true);
      assert(legacyResult.data?.code !== "UPDATE_REQUIRED", `${path} leaves the legacy build usable in compatibility mode`);
    }

    const targetResult = await request("POST", path, { body: bodyForBuild(targetBuild) });
    expectStatus(targetResult, [400, 401], true);
    assert(targetResult.data?.code !== "UPDATE_REQUIRED", `${path} accepts the target build contract`);
  }

  const invalidToken = "contract-probe-invalid-token";
  const protectedRoutes = [
    ["GET", "/api/auth/validate-session", undefined, 401],
    // Logout is intentionally idempotent on the current backend: an already
    // invalid token may return a structured success without changing data.
    ["POST", "/api/auth/logout", {}, [200, 401]],
    ["POST", "/api/push/register", { token: "contract-probe", platform, deviceId: "contract-probe" }, 401],
    ["GET", "/api/hr/staff/security?staffId=contract-probe", undefined, 401],
    ["POST", "/api/hr/staff/device-reset", { staffId: "contract-probe" }, 401],
    ["POST", "/api/hr/staff/device-reset/bulk", { staffIds: ["contract-probe"] }, 401],
  ];
  for (const [method, path, body, expectedStatus] of protectedRoutes) {
    const result = await request(method, path, { token: invalidToken, body });
    expectStatus(result, expectedStatus, true);
  }
}

async function staffSecurityContracts() {
  const invalidToken = "contract-probe-invalid-token";
  const routes = [
    ["GET", "/api/hr/staff/security?staffId=contract-probe", undefined],
    ["POST", "/api/hr/staff/device-reset", { staffId: "contract-probe" }],
    ["GET", "/api/hr/staff/active-logins", undefined],
    ["GET", "/api/hr/staff/active-sessions?staffId=contract-probe", undefined],
    ["POST", "/api/hr/staff/force-logout", { staffId: "contract-probe" }],
    ["POST", "/api/hr/staff/logout-device", { staffId: "contract-probe", sessionIds: ["contract-probe"] }],
    ["POST", "/api/hr/staff/logout-everywhere", { staffId: "contract-probe" }],
    ["GET", "/api/hr/staff/password-status?staffId=contract-probe", undefined],
    ["POST", "/api/hr/staff/set-password", { staffId: "contract-probe", newPassword: "Contract1!", mustChangePassword: true }],
    ["POST", "/api/hr/staff/password-expiry-exempt", { staffId: "contract-probe", exempt: false }],
    // Keep the newly supplied route last so every existing Security endpoint
    // is reported even while a backend deployment is still pending.
    ["POST", "/api/hr/staff/device-reset/bulk", { staffIds: ["contract-probe"] }],
    ["GET", "/api/hr/staff/selectable-ids?status=active&department=Sales&query=contract-probe", undefined],
  ];
  for (const [method, path, body] of routes) {
    const result = await request(method, path, { token: invalidToken, body });
    expectStatus(result, 401, true);
  }
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
  node scripts/check-mobile-api.mjs cp-sv-contracts
  node scripts/check-mobile-api.mjs geotrack-direct-contracts [--geo-base-url https://api-geo.theairix.com/]
  node scripts/check-mobile-api.mjs storage-contracts [--storage-base-url https://mg.theairix.com/]
  node scripts/check-mobile-api.mjs device-login-contracts
  node scripts/check-mobile-api.mjs device-rollout-contracts --rollout-mode compatibility [--legacy-build 71 --target-build 72]
  node scripts/check-mobile-api.mjs device-rollout-contracts --rollout-mode enforced [--legacy-build 71 --target-build 72]
  node scripts/check-mobile-api.mjs auth-recovery-contracts
  node scripts/check-mobile-api.mjs staff-security-contracts
  node scripts/check-mobile-api.mjs joint-read
  node scripts/check-mobile-api.mjs joint-flow --allow-write
  node scripts/check-mobile-api.mjs request --method GET --path /api/health [--token-env MCONNECT_TOKEN]

Safe defaults:
  contracts performs unauthenticated route/auth-contract probes only.
  cp-sv-contracts audits the full CP/SV/booking/collection/tracking/storage route surface without mutations.
  geotrack-direct-contracts proves both live reads work and every invalid-bearer mobile write is rejected with HTTP 401.
  storage-contracts verifies preferred storage routes reject unauthenticated calls and never uploads bytes.
  Set MCONNECT_STORAGE_READ_ID to include a non-mutating real-file 307 redirect check.
  device-login-contracts verifies build-aware login routes using empty credentials and never sends an OTP.
  device-rollout-contracts verifies app-version, legacy/target auth gating, and protected support routes without real credentials.
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
  else if (command === "cp-sv-contracts") await cpSvContracts();
  else if (command === "geotrack-direct-contracts") await geoTrackDirectContracts();
  else if (command === "storage-contracts") await storageContracts();
  else if (command === "device-login-contracts") await deviceLoginContracts();
  else if (command === "device-rollout-contracts") await deviceRolloutContracts();
  else if (command === "auth-recovery-contracts") await authRecoveryContracts();
  else if (command === "staff-security-contracts") await staffSecurityContracts();
  else if (command === "joint-read") await jointReadAudit();
  else if (command === "joint-flow") await jointFullFlow();
  else if (command === "request") await customRequest();
  else throw new Error(`Unknown command: ${command}`);
  if (!args.help) console.log(`\nPASS ${command}: ${results.length} request(s) completed.`);
} catch (error) {
  console.error(`\nFAIL ${command}: ${error instanceof Error ? error.message : String(error)}`);
  process.exitCode = 1;
}
