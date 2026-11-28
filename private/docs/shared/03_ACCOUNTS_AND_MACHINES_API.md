# Accounts and machines contract

> **Generated copy, do not edit.** The source is the Cosmos–AutoPilot hub: `~/Projects/Agents/CosmosAutoPilotHub/docs/shared/03_ACCOUNTS_AND_MACHINES_API.md`. Edit it there; `tools/sync_shared_docs.py` regenerates this file.

Last checked: 28 November 2026. Owner: Cosmos (the server owns the contract; AutoPilot adapts to it).

Cosmos accounts are created only by the admin, and every account works only on the machines the admin approved. A
machine is identified by the AutoPilot app; the browser on that machine is linked through the app. This replaces open
registration and changes sign-in in [01_QA_SYNC_API.md](01_QA_SYNC_API.md#signing-in).

On this page:

- [The admin](#the-admin)
- [Accounts](#accounts)
- [Machines](#machines)
- [Signing in from the app](#signing-in-from-the-app)
- [Linking the browser on a machine](#linking-the-browser-on-a-machine)
- [Signing in on the website](#signing-in-on-the-website)
- [Admin routes (website and app)](#admin-routes-website-and-app)
- [Errors](#errors)

## The admin

- There is exactly **one admin**. It is created (or an existing account is made admin) only on the server with
  `npm run admin:create -- --email <email> --name "<name>"`, which refuses while another admin exists, asks for the
  password on the terminal (never as an argument) and prints nothing secret. No API route creates, promotes or demotes
  an admin. `npm run admin:create -- --replace` hands the role to another account and is the only way to change it.
- The admin's own machines are approved automatically when the admin signs in from the app (the admin is trusted).

## Accounts

- Open registration is removed: `POST /api/auth/register` answers `404`, and the website has no "Create account".
- The admin creates each account with a name, an email, a **machine limit** (default 1, at most 20) and a temporary
  password shown once to the admin. The account must choose a new password at its first sign-in
  (`mustChangePassword`), on the website or in the app.
- An account is `active` or `disabled`. Disabling ends all its sessions and machine links at once.
- The admin can reset an account's password (a new temporary password, shown once) and change its machine limit.

## Machines

- **Machine ID:** AutoPilot computes `machineId` = lowercase hex SHA-256 of `"autopilot-machine-v1:" + <platform
  identifier>`: macOS `IOPlatformUUID`, Windows `HKLM\SOFTWARE\Microsoft\Cryptography\MachineGuid`, Linux
  `/etc/machine-id`, Android `Settings.Secure.ANDROID_ID`. Only the hash leaves the computer.
- **Machine record** (per account): `id`, `machineId`, `label` (the app's device name, for example `AutoPilot on
  MacBook`), `platform` (`windows`, `macos`, `linux`, `android`), `status` (`pending`, `approved`, `revoked`),
  `firstSeenAt`, `lastSeenAt`, `approvedAt`. The same physical machine may appear under several accounts.
- **Limit:** an account may have at most its machine limit of `approved` machines. Approving beyond it answers `409`;
  the admin raises the limit or revokes another machine first. Pending machines don't count.
- **Revoking** a machine ends that machine's app sessions and browser links for that account at once.

## Signing in from the app

`POST /api/auth/app/login` now takes `{ "email", "password", "deviceName", "machineId", "platform" }`.

- Approved machine: `200 AppSession` as before, plus `"machine": { "id", "status": "approved" }` and
  `"mustChangePassword": boolean`.
- Unknown machine: the server records it as `pending` and answers `403 { "error": "This computer is waiting for the
  admin's approval.", "code": "MACHINE_PENDING" }`. The app says so and offers **Try again**. A pending machine of the
  admin is approved straight away instead.
- Revoked machine: `403 { "code": "MACHINE_REVOKED" }`. Disabled account: `403 { "code": "ACCOUNT_DISABLED" }`.
- `mustChangePassword`: the session can only call `POST /api/auth/app/change-password`
  `{ "currentPassword", "newPassword" }` (at least 12 characters) and `logout` until the password is changed; anything
  else answers `403 { "code": "PASSWORD_CHANGE_REQUIRED" }`.
- Refresh tokens and app access tokens carry the machine; they stop working as soon as the machine is revoked or the
  account disabled.

## Linking the browser on a machine

1. In the signed-in app, **Open Cosmos** calls `POST /api/auth/app/browser-link` and gets
   `{ "url": "https://cosmos.naikio.in/link#<token>", "expiresIn": 60 }`. The token is random (at least 32 bytes),
   stored only as a SHA-256 hash, valid for 60 seconds and usable once.
2. The app opens that URL in the computer's default browser. The token is in the URL **fragment**, so it never reaches
   server logs or `Referer` headers; the page reads it and calls `POST /api/auth/browser-link/redeem` `{ "token" }`.
3. The server marks the token used (`UPDATE … SET used_at … RETURNING`), and answers with the website session cookie
   (`cosmos_token`, as today) plus a **machine cookie** `__Secure-cosmos_machine` (HttpOnly, Secure,
   SameSite=Strict, path `/api`, 400 days), whose value is a random token stored hashed and bound to that account and
   machine. The page then opens the dashboard.
4. A used, expired or unknown token answers `400 { "error": "This link has expired. Open Cosmos from the AutoPilot
   app again." }`.

## Signing in on the website

- `POST /api/auth/login` checks the email and password first: an unknown email and a wrong password both answer
  `401 { "error": "Invalid email or password" }`, with or without a machine cookie. Only after a correct password does
  it answer `403 { "code": "ACCOUNT_DISABLED" }` or, without a valid machine cookie for an **approved** machine of that
  account, `403 { "code": "MACHINE_LINK_REQUIRED", "error": "Open Cosmos from the AutoPilot app on your computer to
  sign in here." }`.
- Every cookie-authenticated request checks the machine cookie too, so revoking the machine signs that browser out.
- Signing out on the website keeps the machine cookie (the browser stays linked); revoking the machine removes it.
- Forgotten password: the OTP reset still works, but only from a linked browser.

## Admin routes (website and app)

All under `/api/admin`, admin only (role read from the database), from a linked browser or an approved app session.
Responses never include password hashes, tokens or machine cookies.

| Route | Purpose |
| --- | --- |
| `GET /api/admin/accounts?status=&q=&page=&pageSize=` | `{ "accounts": [Account], "page", "pageSize", "total" }` |
| `POST /api/admin/accounts` | `{ "name", "email", "machineLimit" }` → `201 { "account": Account, "temporaryPassword" }` (shown once) |
| `PATCH /api/admin/accounts/:id` | `{ "name"?, "machineLimit"?, "status"? }` → `{ "account": Account }` |
| `POST /api/admin/accounts/:id/reset-password` | `{ "temporaryPassword" }` (shown once; ends the account's sessions) |
| `GET /api/admin/machines?status=pending&page=&pageSize=` | `{ "machines": [Machine & { "account": { "id", "name", "email" } }], "page", "pageSize", "total" }` |
| `POST /api/admin/machines/:id/approve` | `{ "machine": Machine }`; `409` beyond the account's limit |
| `POST /api/admin/machines/:id/revoke` | `{ "machine": Machine }` |

`Account`: `{ "id", "name", "email", "role", "status", "machineLimit", "approvedMachines", "pendingMachines",
"mustChangePassword", "createdAt", "lastSignInAt" }`. `Machine`: the record above.

## Errors

As in the Q&A contract: `{ "error": "<sentence>", "code"?: "<CODE>" }`, rate limits on sign-in, password changes,
browser links and admin writes, and no account enumeration (sign-in answers the same for an unknown email as for a
wrong password, before any machine check reveals anything).
