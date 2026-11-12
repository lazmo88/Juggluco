/*      This file is part of Juggluco, an Android app to receive and display         */
/*      glucose values from Freestyle Libre 2(+), Libre 3(+), Dexcom G7/ONE+,        */
/*      Sibionics GS1Sb and GS3, Accu-Chek SmartGuide, CareSens Air and              */
/*      Aidex X sensors.                                                             */
/*                                                                                   */
/*      Copyright (C) 2021 Jaap Korthals Altes <jaapkorthalsaltes@gmail.com>         */
/*                                                                                   */
/*      Juggluco is free software: you can redistribute it and/or modify             */
/*      it under the terms of the GNU General Public License as published            */
/*      by the Free Software Foundation, either version 3 of the License, or         */
/*      (at your option) any later version.                                          */
/*                                                                                   */
/*      Juggluco is distributed in the hope that it will be useful, but              */
/*      WITHOUT ANY WARRANTY; without even the implied warranty of                   */
/*      MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.                         */
/*      See the GNU General Public License for more details.                         */
/*                                                                                   */
/*      You should have received a copy of the GNU General Public License            */
/*      along with Juggluco. If not, see <https://www.gnu.org/licenses/>.            */
/*                                                                                   */
/*      Thu Oct 15 12:54:42 CEST 2026                                                */

// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "http.hpp"
#include <nlohmann/json.hpp>
#include <cstdint>
#include <functional>
#include <memory>
#include <string_view>

namespace jgchat {
using Json = nlohmann::json;
struct Tokens {
    std::string access_token, refresh_token, id_token, account_id;
    std::int64_t expires_at = 0;
};
struct DeviceCode {
    std::string verification_url, user_code, device_auth_id;
    unsigned interval_seconds = 5;
    std::chrono::steady_clock::time_point deadline;
};
DeviceCode request_device_code(const HttpClient&, const std::atomic_bool&);
Tokens complete_device_login(const HttpClient&, const DeviceCode&, const std::atomic_bool&);

// Browser sign-in: authorization code + PKCE with a single-use loopback
// callback listener, as in Codex CLI's `codex login` (login/src/server.rs).
// The verifier and state never leave native memory; only the authorize URL
// (which carries the S256 challenge and state, as any OAuth URL does) is
// handed to the browser.
struct BrowserLogin {
    BrowserLogin() = default;
    BrowserLogin(const BrowserLogin&) = delete;
    BrowserLogin& operator=(const BrowserLogin&) = delete;
    ~BrowserLogin();
    void close_listener() noexcept;
    std::string authorize_url;
    // Secrets: never log, display or hand to Java.
    std::string redirect_uri, state, code_verifier;
    int listener = -1;
    unsigned short port = 0;
    std::chrono::steady_clock::time_point deadline;
};
// Binds 127.0.0.1:1455, or the registered fallback 127.0.0.1:1457 when 1455 is
// taken. Throws UiCode::sign_in_port_busy when neither port is free.
std::unique_ptr<BrowserLogin> start_browser_login(std::chrono::seconds timeout = std::chrono::minutes(10));
// Waits for one GET /auth/callback with the expected state (other requests
// are answered and ignored), closes the listener, exchanges the code with the
// same redirect_uri and answers the browser with a short result page that
// links back to `return_link`. `on_callback` runs once a valid callback arrives.
Tokens complete_browser_login(const HttpClient&, BrowserLogin&, std::string_view return_link,
    const std::atomic_bool& cancel, const std::function<void()>& on_callback);

// Display-only account details from the ID token claims. Never throws.
struct AccountInfo { std::string email, plan; };
AccountInfo account_info(const Tokens&) noexcept;
// Exposed for tests.
std::string pkce_challenge(std::string_view verifier);
Tokens refresh_tokens(const HttpClient&, const Tokens&, const std::atomic_bool&);
Json tokens_to_json(const Tokens&);
Tokens tokens_from_json(const Json&);
}
