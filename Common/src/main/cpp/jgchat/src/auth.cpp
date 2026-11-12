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
/*      Thu Oct 15 12:53:38 CEST 2026                                                */

// SPDX-License-Identifier: GPL-3.0-or-later
// Device authorization and refresh flow adapted from OpenAI Codex, Copyright
// 2025 OpenAI, Apache-2.0, commit e72da2b53805894878023d01949a25a082e0a5cb:
// codex-rs/login/src/{device_code_auth.rs,server.rs,oauth/client.rs,
// auth/manager.rs,token_data.rs}. See licenses/Codex-{Apache-2.0,NOTICE}.txt.
// Browser sign-in (authorization code + PKCE with a loopback callback) adapted
// from the same project at commit 27294165dc163ce67bcb7fd68a7ac745fe20d517:
// codex-rs/login/src/{server.rs,oauth/authorization.rs,oauth/pkce.rs}.
#include "jgchat/ui_messages.hpp"
#include "jgchat/auth.hpp"
#include "jgchat/diagnostics.hpp"

#include <algorithm>
#include <array>
#include <cerrno>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <optional>
#include <vector>

#include <arpa/inet.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>
#include <stdexcept>
#include <string_view>
#include <thread>

namespace jgchat {
namespace {
constexpr std::string_view client_id = "app_EMoamEEZ73f0CkXaXp7hrann";
constexpr std::string_view issuer_host = "auth.openai.com";
constexpr std::string_view callback = "https://auth.openai.com/deviceauth/callback";
constexpr std::string_view verification = "https://auth.openai.com/codex/device";
constexpr std::size_t response_limit = 1024 * 1024;
constexpr std::size_t token_limit = 64 * 1024;
using Clock = std::chrono::steady_clock;
using Milliseconds = std::chrono::milliseconds;

[[noreturn]] void invalid() {
    throw jgchat::UiError(jgchat::UiCode::invalid_authentication_response);
}
void cancelled(const std::atomic_bool& cancel) {
    if (cancel.load()) throw jgchat::UiError(jgchat::UiCode::authentication_cancelled);
}
bool success(long status) { return status >= 200 && status < 300; }
bool safe_string(std::string_view value, std::size_t limit) {
    return !value.empty() && value.size() <= limit &&
        std::all_of(value.begin(), value.end(), [](unsigned char c) { return c > 32 && c < 127; });
}
std::string string_field(const Json& object, const char* name, std::size_t limit,
                         bool required = true) {
    const auto it = object.find(name);
    if (it == object.end() || it->is_null()) {
        if (required) invalid();
        return {};
    }
    if (!it->is_string()) invalid();
    auto value = it->get<std::string>();
    if (!safe_string(value, limit)) invalid();
    return value;
}

// Bound nesting before building a DOM, including for attacker-controlled JWT
// metadata. JSON syntax/escaping is subsequently checked by the JSON parser.
Json parse_object(std::string_view text, std::size_t limit = response_limit) {
    if (text.size() > limit) invalid();
    unsigned depth = 0;
    bool quoted = false, escape = false;
    for (const char c : text) {
        if (quoted) {
            if (escape) escape = false;
            else if (c == '\\') escape = true;
            else if (c == '"') quoted = false;
        } else if (c == '"') quoted = true;
        else if (c == '{' || c == '[') {
            if (++depth > 32) invalid();
        } else if (c == '}' || c == ']') {
            if (!depth) invalid();
            --depth;
        }
    }
    auto result = Json::parse(text.begin(), text.end(), nullptr, false);
    if (result.is_discarded() || !result.is_object()) invalid();
    return result;
}

std::uint64_t unsigned_integer(const Json& value) {
    if (value.is_number_unsigned()) return value.get<std::uint64_t>();
    if (!value.is_number_integer()) invalid();
    const auto result = value.get<std::int64_t>();
    if (result < 0) invalid();
    return static_cast<std::uint64_t>(result);
}
std::int64_t epoch_seconds() {
    return std::chrono::duration_cast<std::chrono::seconds>(
        std::chrono::system_clock::now().time_since_epoch()).count();
}
std::int64_t epoch_field(const Json& value) {
    const auto result = unsigned_integer(value);
    if (result > static_cast<std::uint64_t>(std::numeric_limits<std::int64_t>::max())) invalid();
    return static_cast<std::int64_t>(result);
}
unsigned interval_field(const Json& object) {
    const auto it = object.find("interval");
    if (it == object.end()) return 5;
    std::uint64_t value = 0;
    if (it->is_string()) {
        auto raw = it->get<std::string>();
        const auto first = raw.find_first_not_of(" \t\r\n");
        const auto last = raw.find_last_not_of(" \t\r\n");
        if (first == std::string::npos || last - first > 20) invalid();
        for (std::size_t i = first; i <= last; ++i) {
            if (raw[i] < '0' || raw[i] > '9') invalid();
            const auto digit = static_cast<unsigned>(raw[i] - '0');
            if (value > (std::numeric_limits<unsigned>::max() - digit) / 10U) invalid();
            value = value * 10U + digit;
        }
    } else value = unsigned_integer(*it);
    if (value > std::numeric_limits<unsigned>::max()) invalid();
    return std::max(1U, static_cast<unsigned>(value));
}
Milliseconds remaining(Clock::time_point deadline, const std::atomic_bool& cancel) {
    cancelled(cancel);
    const auto now = Clock::now();
    if (now >= deadline) throw jgchat::UiError(jgchat::UiCode::device_authorization_expired);
    return std::max(Milliseconds(1), std::min(Milliseconds(60000),
        std::chrono::duration_cast<Milliseconds>(deadline - now)));
}
HttpRequest request(std::string path, std::string body, bool form = false) {
    HttpRequest result;
    result.host = issuer_host;
    result.path = std::move(path);
    result.body = std::move(body);
    result.headers.emplace_back("Content-Type", form ? "application/x-www-form-urlencoded" : "application/json");
    result.headers.emplace_back("Accept", "application/json");
    result.max_response_bytes = response_limit;
    return result;
}
HttpResponse send_request(const HttpClient& http, const HttpRequest& req,
                    const std::atomic_bool& cancel, bool retain_success = false) {
    DiagnosticOperation trace("authentication_http");
    const char* endpoint = req.path == "/api/accounts/deviceauth/usercode" ? "device_code" :
        req.path == "/api/accounts/deviceauth/token" ? "device_poll" : req.path == "/oauth/token" ? "token" : "other";
    diagnostic("auth endpoint=%s request_bytes=%zu timeout_ms=%lld", endpoint, req.body.size(),
        static_cast<long long>(req.timeout.count()));
    cancelled(cancel);
    HttpResponse result;
    trace.stage("transport");
    try { result = http(req, cancel); }
    catch (...) {
        diagnostic("auth transport exception cancel=%d", cancel.load() ? 1 : 0);
        if (cancel.load()) trace.cancelled();
        cancelled(cancel);
        // A transport exception can contain request bodies and bearer tokens.
        throw jgchat::UiError(jgchat::UiCode::authentication_network_request_failed);
    }
    // A refresh may already have rotated its token. Do not throw away a received
    // success just because cancellation arrived concurrently; the caller must
    // persist the returned credentials before honoring cancellation.
    diagnostic("auth received status=%ld response_bytes=%zu transport_error=%d cancel=%d",
        result.status, result.body.size(), result.error.empty() ? 0 : 1, cancel.load() ? 1 : 0);
    if (cancel.load() && (!retain_success || !success(result.status))) trace.cancelled();
    if (!retain_success || !success(result.status)) cancelled(cancel);
    if (!result.error.empty()) throw jgchat::UiError(jgchat::UiCode::authentication_network_request_failed);
    if (result.body.size() > response_limit) invalid();
    trace.success();
    return result;
}
void require_success(const HttpResponse& response, bool refresh = false) {
    if (success(response.status)) return;
    if (refresh && (response.status == 401 || response.status == 403))
        throw jgchat::UiError(jgchat::UiCode::chatgpt_sign_in_expired_sign_in_again);
    // Do not include the response body, transport error, or OAuth error details.
    throw UiError({UiCode::authentication_http, {std::to_string(response.status)}});
}
std::string form_escape(std::string_view value) {
    constexpr char hex[] = "0123456789ABCDEF";
    std::string result;
    for (unsigned char c : value) {
        if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') ||
            (c >= '0' && c <= '9') || c == '-' || c == '.' || c == '_' || c == '~')
            result.push_back(static_cast<char>(c));
        else {
            result.push_back('%');
            result.push_back(hex[c >> 4]);
            result.push_back(hex[c & 15]);
        }
    }
    return result;
}
int base64_digit(unsigned char c) {
    if (c >= 'A' && c <= 'Z') return c - 'A';
    if (c >= 'a' && c <= 'z') return c - 'a' + 26;
    if (c >= '0' && c <= '9') return c - '0' + 52;
    if (c == '-') return 62;
    if (c == '_') return 63;
    return -1;
}
std::string decode_base64url(std::string_view value) {
    if (value.empty() || value.size() % 4 == 1) invalid();
    std::string result;
    result.reserve(value.size() * 3 / 4);
    unsigned bits = 0, bit_count = 0;
    for (unsigned char c : value) {
        const int digit = base64_digit(c);
        if (digit < 0) invalid();
        bits = (bits << 6U) | static_cast<unsigned>(digit);
        bit_count += 6;
        if (bit_count >= 8) {
            bit_count -= 8;
            result.push_back(static_cast<char>((bits >> bit_count) & 255U));
        }
    }
    if (bit_count && (bits & ((1U << bit_count) - 1U))) invalid();
    return result;
}
struct Metadata { std::string account_id; std::int64_t expires_at = 0; };
Metadata metadata(std::string_view token, bool must_be_jwt) {
    Metadata result;
    const auto first = token.find('.');
    if (first == std::string_view::npos && !must_be_jwt) return result;
    const auto second = first == std::string_view::npos ? first : token.find('.', first + 1);
    if (first == std::string_view::npos || first == 0 || second == std::string_view::npos ||
        second == first + 1 || second + 1 == token.size() || token.find('.', second + 1) != std::string_view::npos)
        invalid();
    // Metadata decoding only: this is NOT JWT signature verification. Tokens
    // originate from a verified HTTPS response (or app-private saved state).
    const auto claims = parse_object(decode_base64url(token.substr(first + 1, second - first - 1)), token_limit);
    const auto auth = claims.find("https://api.openai.com/auth");
    if (auth != claims.end() && !auth->is_null()) {
        if (!auth->is_object()) invalid();
        result.account_id = string_field(*auth, "chatgpt_account_id", 1024, false);
    }
    const auto exp = claims.find("exp");
    if (exp != claims.end() && !exp->is_null()) result.expires_at = epoch_field(*exp);
    return result;
}
void merge_account(std::string& account, const std::string& candidate) {
    if (candidate.empty()) return;
    if (!account.empty() && account != candidate) invalid();
    account = candidate;
}
void validate_tokens(const Tokens& tokens) {
    if (!safe_string(tokens.access_token, token_limit) ||
        !safe_string(tokens.refresh_token, token_limit) ||
        (!tokens.id_token.empty() && !safe_string(tokens.id_token, token_limit)) ||
        !safe_string(tokens.account_id, 1024) || tokens.expires_at < 0) invalid();
}
Tokens token_response(const Json& response, const Tokens* previous = nullptr) {
    Tokens tokens;
    tokens.access_token = string_field(response, "access_token", token_limit);
    tokens.refresh_token = string_field(response, "refresh_token", token_limit, !previous);
    tokens.id_token = string_field(response, "id_token", token_limit, false);
    if (previous) {
        if (tokens.refresh_token.empty()) tokens.refresh_token = previous->refresh_token;
        if (tokens.id_token.empty()) tokens.id_token = previous->id_token;
        tokens.account_id = previous->account_id;
    }
    merge_account(tokens.account_id, string_field(response, "account_id", 1024, false));
    const auto access = metadata(tokens.access_token, false);
    merge_account(tokens.account_id, access.account_id);
    if (!tokens.id_token.empty()) merge_account(tokens.account_id, metadata(tokens.id_token, true).account_id);
    tokens.expires_at = access.expires_at;
    const auto lifetime = response.find("expires_in");
    if (lifetime != response.end() && !lifetime->is_null()) {
        const auto seconds = epoch_field(*lifetime);
        const auto now = epoch_seconds();
        if (now < 0 || seconds > std::numeric_limits<std::int64_t>::max() - now) invalid();
        const auto expiry = now + seconds;
        tokens.expires_at = tokens.expires_at ? std::min(tokens.expires_at, expiry) : expiry;
    }
    validate_tokens(tokens);
    return tokens;
}
void poll_wait(unsigned seconds, Clock::time_point deadline, const std::atomic_bool& cancel) {
    const auto next = std::min(deadline, Clock::now() + std::chrono::seconds(seconds));
    for (;;) {
        cancelled(cancel);
        const auto now = Clock::now();
        if (now >= deadline) throw jgchat::UiError(jgchat::UiCode::device_authorization_expired);
        if (now >= next) return;
        std::this_thread::sleep_for(std::min(Milliseconds(100),
            std::max(Milliseconds(1), std::chrono::duration_cast<Milliseconds>(next - now))));
    }
}
} // namespace

DeviceCode request_device_code(const HttpClient& http, const std::atomic_bool& cancel) {
    const auto deadline = Clock::now() + std::chrono::minutes(15);
    auto req = request("/api/accounts/deviceauth/usercode", Json{{"client_id", client_id}}.dump());
    req.timeout = remaining(deadline, cancel);
    const auto response = send_request(http, req, cancel);
    require_success(response);
    const auto body = parse_object(response.body);
    DeviceCode result;
    result.verification_url = verification;
    result.device_auth_id = string_field(body, "device_auth_id", 4096);
    result.user_code = string_field(body, "user_code", 256, false);
    const auto alias = string_field(body, "usercode", 256, false);
    if (result.user_code.empty()) result.user_code = alias;
    else if (!alias.empty() && alias != result.user_code) invalid();
    if (result.user_code.empty()) invalid();
    result.interval_seconds = interval_field(body);
    result.deadline = deadline;
    return result;
}

Tokens complete_device_login(const HttpClient& http, const DeviceCode& device, const std::atomic_bool& cancel) {
    if (!safe_string(device.device_auth_id, 4096) || !safe_string(device.user_code, 256)) invalid();
    const auto deadline = std::min(device.deadline, Clock::now() + std::chrono::minutes(15));
    for (;;) {
        auto req = request("/api/accounts/deviceauth/token",
            Json{{"device_auth_id", device.device_auth_id}, {"user_code", device.user_code}}.dump());
        req.timeout = remaining(deadline, cancel);
        const auto response = send_request(http, req, cancel);
        if (response.status == 403 || response.status == 404) {
            poll_wait(std::max(1U, device.interval_seconds), deadline, cancel);
            continue;
        }
        require_success(response);
        const auto body = parse_object(response.body);
        const auto code = string_field(body, "authorization_code", token_limit);
        const auto verifier = string_field(body, "code_verifier", 4096);
        (void)string_field(body, "code_challenge", 4096);
        auto exchange = request("/oauth/token", "grant_type=authorization_code&client_id=" +
            form_escape(client_id) + "&code=" + form_escape(code) + "&redirect_uri=" +
            form_escape(callback) + "&code_verifier=" + form_escape(verifier), true);
        exchange.timeout = remaining(deadline, cancel);
        // One-time authorization codes must not be retried after an ambiguous
        // network failure; the server may already have consumed the code.
        const auto exchanged = send_request(http, exchange, cancel, true);
        require_success(exchanged);
        return token_response(parse_object(exchanged.body));
    }
}

Tokens refresh_tokens(const HttpClient& http, const Tokens& previous, const std::atomic_bool& cancel) {
    validate_tokens(previous);
    const auto req = request("/oauth/token", Json{{"client_id", client_id},
        {"grant_type", "refresh_token"}, {"refresh_token", previous.refresh_token}}.dump());
    // Refresh tokens may rotate. Never retry here; the caller serializes refresh
    // and persists the entire successful result immediately.
    const auto response = send_request(http, req, cancel, true);
    require_success(response, true);
    return token_response(parse_object(response.body), &previous);
}

Json tokens_to_json(const Tokens& tokens) {
    validate_tokens(tokens);
    return Json{{"access_token", tokens.access_token}, {"refresh_token", tokens.refresh_token},
        {"id_token", tokens.id_token}, {"account_id", tokens.account_id}, {"expires_at", tokens.expires_at}};
}

Tokens tokens_from_json(const Json& body) {
    if (!body.is_object()) invalid();
    Tokens tokens;
    tokens.access_token = string_field(body, "access_token", token_limit);
    tokens.refresh_token = string_field(body, "refresh_token", token_limit);
    // Empty ID token is the serialized representation when the server omits it.
    const auto id = body.find("id_token");
    if (id != body.end() && !id->is_null()) {
        if (!id->is_string()) invalid();
        tokens.id_token = id->get<std::string>();
    }
    tokens.account_id = string_field(body, "account_id", 1024);
    const auto expiry = body.find("expires_at");
    if (expiry != body.end() && !expiry->is_null()) tokens.expires_at = epoch_field(*expiry);
    validate_tokens(tokens);
    std::string account = tokens.account_id;
    merge_account(account, metadata(tokens.access_token, false).account_id);
    if (!tokens.id_token.empty()) merge_account(account, metadata(tokens.id_token, true).account_id);
    return tokens;
}
// ---------------------------------------------------------------------------
// Browser sign-in: authorization code + PKCE with a single-use loopback
// listener. Parameters match Codex CLI `codex login`:
//   server.rs  DEFAULT_PORT 1455, FALLBACK_PORT 1457 ("Keep in sync with the
//              Codex CLI Hydra redirect URI allow-list"), redirect_uri
//              http://127.0.0.1:{port}/auth/callback, build_authorize_url()
//              scope and extra parameters, exchange_code_for_tokens().
//   oauth/authorization.rs  build_authorization_url(), generate_state().
//   oauth/pkce.rs           64 random bytes -> base64url verifier, S256.
// ---------------------------------------------------------------------------
namespace {
constexpr unsigned short browser_ports[] = {1455, 1457};
constexpr std::string_view callback_path = "/auth/callback";
constexpr std::string_view authorize_scope =
    "openid profile email offline_access api.connectors.read api.connectors.invoke";
constexpr std::string_view originator = "codex_cli_rs";
constexpr std::size_t max_request_bytes = 16 * 1024;
constexpr std::size_t max_clients = 8;
constexpr auto client_idle_limit = std::chrono::seconds(15);

std::array<std::uint8_t, 32> sha256(std::string_view data) {
    static constexpr std::uint32_t k[64] = {
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2};
    std::uint32_t h[8] = {0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
                          0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19};
    auto rotr = [](std::uint32_t x, unsigned n) { return (x >> n) | (x << (32 - n)); };
    std::string message(data);
    const std::uint64_t bits = static_cast<std::uint64_t>(data.size()) * 8U;
    message.push_back(static_cast<char>(0x80));
    while (message.size() % 64 != 56) message.push_back('\0');
    for (int i = 7; i >= 0; --i) message.push_back(static_cast<char>((bits >> (i * 8)) & 0xffU));
    for (std::size_t block = 0; block < message.size(); block += 64) {
        std::uint32_t w[64];
        for (int i = 0; i < 16; ++i) {
            const auto* p = reinterpret_cast<const unsigned char*>(message.data() + block + i * 4);
            w[i] = (std::uint32_t(p[0]) << 24) | (std::uint32_t(p[1]) << 16) | (std::uint32_t(p[2]) << 8) | p[3];
        }
        for (int i = 16; i < 64; ++i) {
            const auto s0 = rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >> 3);
            const auto s1 = rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >> 10);
            w[i] = w[i - 16] + s0 + w[i - 7] + s1;
        }
        std::uint32_t a = h[0], b = h[1], c = h[2], d = h[3], e = h[4], f = h[5], g = h[6], hh = h[7];
        for (int i = 0; i < 64; ++i) {
            const auto t1 = hh + (rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25)) + ((e & f) ^ (~e & g)) + k[i] + w[i];
            const auto t2 = (rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22)) + ((a & b) ^ (a & c) ^ (b & c));
            hh = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2;
        }
        h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e; h[5] += f; h[6] += g; h[7] += hh;
    }
    std::fill(message.begin(), message.end(), '\0');
    std::array<std::uint8_t, 32> out{};
    for (int i = 0; i < 8; ++i)
        for (int j = 0; j < 4; ++j) out[i * 4 + j] = static_cast<std::uint8_t>(h[i] >> (24 - j * 8));
    return out;
}
std::string base64url(const unsigned char* data, std::size_t size) {
    constexpr char alphabet[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    std::string out;
    out.reserve((size * 4 + 2) / 3);
    std::size_t i = 0;
    for (; i + 2 < size; i += 3) {
        const unsigned v = (unsigned(data[i]) << 16) | (unsigned(data[i + 1]) << 8) | data[i + 2];
        out += alphabet[(v >> 18) & 63]; out += alphabet[(v >> 12) & 63];
        out += alphabet[(v >> 6) & 63]; out += alphabet[v & 63];
    }
    if (size - i == 1) {
        const unsigned v = unsigned(data[i]) << 16;
        out += alphabet[(v >> 18) & 63]; out += alphabet[(v >> 12) & 63];
    } else if (size - i == 2) {
        const unsigned v = (unsigned(data[i]) << 16) | (unsigned(data[i + 1]) << 8);
        out += alphabet[(v >> 18) & 63]; out += alphabet[(v >> 12) & 63]; out += alphabet[(v >> 6) & 63];
    }
    return out;
}
std::string random_token(std::size_t bytes) {
    std::vector<unsigned char> buffer(bytes);
    arc4random_buf(buffer.data(), buffer.size()); // Bionic/glibc CSPRNG.
    auto out = base64url(buffer.data(), buffer.size());
    std::fill(buffer.begin(), buffer.end(), 0);
    return out;
}
bool same_secret(std::string_view a, std::string_view b) {
    if (a.size() != b.size() || a.empty()) return false;
    unsigned char difference = 0;
    for (std::size_t i = 0; i < a.size(); ++i)
        difference |= static_cast<unsigned char>(a[i] ^ b[i]);
    return difference == 0;
}
int hex_digit(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}
std::optional<std::string> url_decode(std::string_view value) {
    std::string out;
    out.reserve(value.size());
    for (std::size_t i = 0; i < value.size(); ++i) {
        if (value[i] == '+') out += ' ';
        else if (value[i] == '%') {
            if (i + 2 >= value.size()) return std::nullopt;
            const int hi = hex_digit(value[i + 1]), lo = hex_digit(value[i + 2]);
            if (hi < 0 || lo < 0) return std::nullopt;
            out += static_cast<char>(hi * 16 + lo);
            i += 2;
        } else out += value[i];
    }
    return out;
}
struct CallbackParameters { std::optional<std::string> code, state, error; };
CallbackParameters callback_parameters(std::string_view query) {
    CallbackParameters out;
    while (!query.empty()) {
        const auto end = query.find('&');
        const auto pair = query.substr(0, end);
        query = end == std::string_view::npos ? std::string_view{} : query.substr(end + 1);
        const auto equals = pair.find('=');
        const auto name = pair.substr(0, equals);
        const auto value = url_decode(equals == std::string_view::npos ? std::string_view{} : pair.substr(equals + 1));
        if (!value) continue;
        // First occurrence wins; a duplicated parameter cannot replace it.
        if (name == "code" && !out.code) out.code = *value;
        else if (name == "state" && !out.state) out.state = *value;
        else if (name == "error" && !out.error) out.error = *value;
    }
    return out;
}
std::string html_escape(std::string_view value) {
    std::string out;
    for (const char c : value) {
        switch (c) {
            case '&': out += "&amp;"; break;
            case '<': out += "&lt;"; break;
            case '>': out += "&gt;"; break;
            case '"': out += "&quot;"; break;
            case '\'': out += "&#39;"; break;
            default: out += c;
        }
    }
    return out;
}
std::string result_page(bool ok, std::string_view return_link) {
    const std::string_view title = ok ? "Signed in to Juggluco" : "Sign-in could not be completed";
    const std::string_view text = ok ? "You can return to the app."
                                     : "Return to Juggluco and try again.";
    std::string page =
        "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
        "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
        "<title>" + html_escape(title) + "</title><style>"
        "body{font-family:system-ui,sans-serif;margin:0;min-height:100vh;display:flex;align-items:center;"
        "justify-content:center;background:#fdf8f3;color:#1f1b16}"
        "@media(prefers-color-scheme:dark){body{background:#17130e;color:#ece0d6}}"
        "main{max-width:28rem;padding:2rem;text-align:center}h1{font-size:1.5rem;font-weight:500}"
        "a{display:inline-block;margin-top:1rem;padding:.75rem 1.5rem;border-radius:999px;"
        "background:#8b5000;color:#fff;text-decoration:none;font-weight:500}"
        "</style></head><body><main><h1>" + html_escape(title) + "</h1><p>" + html_escape(text) + "</p>";
    if (!return_link.empty())
        page += "<a href=\"" + html_escape(return_link) + "\">Return to Juggluco</a>";
    page += "</main></body></html>";
    return page;
}
void send_all(int fd, std::string_view data) {
    std::size_t sent = 0;
    unsigned attempts = 0;
    while (sent < data.size() && attempts < 100) {
        const auto n = ::send(fd, data.data() + sent, data.size() - sent, MSG_NOSIGNAL);
        if (n > 0) { sent += static_cast<std::size_t>(n); continue; }
        if (n < 0 && errno == EINTR) continue;
        if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            ++attempts;
            pollfd p{fd, POLLOUT, 0};
            ::poll(&p, 1, 50);
            continue;
        }
        return;
    }
}
void respond(int fd, int status, std::string_view reason, std::string_view body, bool html) {
    std::string response = "HTTP/1.1 " + std::to_string(status) + " " + std::string(reason) + "\r\n";
    response += html ? "Content-Type: text/html; charset=utf-8\r\n" : "Content-Type: text/plain; charset=utf-8\r\n";
    response += "Content-Length: " + std::to_string(body.size()) + "\r\n"
        "Cache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\n"
        "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'\r\n"
        "Connection: close\r\n\r\n";
    response += body;
    send_all(fd, response);
}
int bind_loopback(unsigned short port, int& error) {
    const int fd = ::socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    if (fd < 0) { error = errno; return -1; }
    // Allows rebinding over TIME_WAIT from a previous attempt. Linux still
    // refuses the bind while any other socket is listening on the port.
    const int one = 1;
    ::setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    sockaddr_in address{};
    address.sin_family = AF_INET;
    address.sin_port = htons(port);
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (::bind(fd, reinterpret_cast<const sockaddr*>(&address), sizeof(address)) != 0 || ::listen(fd, 8) != 0) {
        error = errno;
        ::close(fd);
        return -1;
    }
    return fd;
}
struct Connection {
    int fd = -1;
    std::string data;
    Clock::time_point since;
};
void close_connection(Connection& connection) noexcept {
    if (connection.fd >= 0) ::close(connection.fd);
    connection.fd = -1;
    std::fill(connection.data.begin(), connection.data.end(), '\0');
    connection.data.clear();
}
struct Connections {
    std::vector<Connection> list;
    ~Connections() { for (auto& c : list) close_connection(c); }
};
} // namespace

std::string pkce_challenge(std::string_view verifier) {
    const auto digest = sha256(verifier);
    return base64url(digest.data(), digest.size());
}

void BrowserLogin::close_listener() noexcept {
    if (listener >= 0) {
        ::close(listener);
        diagnostic("browser login listener closed port=%u", static_cast<unsigned>(port));
    }
    listener = -1;
}
BrowserLogin::~BrowserLogin() {
    close_listener();
    std::fill(code_verifier.begin(), code_verifier.end(), '\0');
    std::fill(state.begin(), state.end(), '\0');
}

std::unique_ptr<BrowserLogin> start_browser_login(std::chrono::seconds timeout) {
    auto login = std::make_unique<BrowserLogin>();
    int error = 0;
    for (const auto port : browser_ports) {
        const int fd = bind_loopback(port, error);
        if (fd >= 0) { login->listener = fd; login->port = port; break; }
        diagnostic("browser login port unavailable port=%u errno=%d", static_cast<unsigned>(port), error);
    }
    if (login->listener < 0) throw UiError(UiCode::sign_in_port_busy);
    login->state = random_token(32);
    login->code_verifier = random_token(64);
    login->redirect_uri = "http://127.0.0.1:" + std::to_string(login->port) + std::string(callback_path);
    login->authorize_url = "https://" + std::string(issuer_host) + "/oauth/authorize"
        "?response_type=code&client_id=" + form_escape(client_id) +
        "&redirect_uri=" + form_escape(login->redirect_uri) +
        "&code_challenge=" + form_escape(pkce_challenge(login->code_verifier)) +
        "&code_challenge_method=S256&state=" + form_escape(login->state) +
        "&scope=" + form_escape(authorize_scope) +
        "&id_token_add_organizations=true&codex_cli_simplified_flow=true&originator=" + form_escape(originator);
    login->deadline = Clock::now() + timeout;
    diagnostic("browser login listening port=%u timeout_s=%lld", static_cast<unsigned>(login->port),
        static_cast<long long>(timeout.count()));
    return login;
}

Tokens complete_browser_login(const HttpClient& http, BrowserLogin& login, std::string_view return_link,
                              const std::atomic_bool& cancel, const std::function<void()>& on_callback) {
    if (login.listener < 0 || login.state.empty() || login.code_verifier.empty()) invalid();
    Connections connections;
    auto& clients = connections.list;
    for (;;) {
        if (cancel.load()) { login.close_listener(); cancelled(cancel); }
        const auto now = Clock::now();
        if (now >= login.deadline) {
            login.close_listener();
            throw UiError(UiCode::browser_sign_in_timed_out);
        }
        // Drop idle connections, e.g. speculative browser preconnects.
        clients.erase(std::remove_if(clients.begin(), clients.end(), [&](Connection& c) {
            if (now - c.since < client_idle_limit) return false;
            close_connection(c);
            return true;
        }), clients.end());

        std::vector<pollfd> fds;
        fds.push_back({login.listener, POLLIN, 0});
        for (const auto& c : clients) fds.push_back({c.fd, POLLIN, 0});
        const int ready = ::poll(fds.data(), static_cast<nfds_t>(fds.size()), 200);
        if (ready < 0 && errno != EINTR) {
            login.close_listener();
            throw UiError(UiCode::authentication_network_request_failed);
        }
        if (ready <= 0) continue;

        if (fds[0].revents & POLLIN) {
            for (;;) {
                const int fd = ::accept4(login.listener, nullptr, nullptr, SOCK_CLOEXEC | SOCK_NONBLOCK);
                if (fd < 0) break;
                if (clients.size() >= max_clients) {
                    close_connection(clients.front());
                    clients.erase(clients.begin());
                }
                clients.push_back({fd, {}, Clock::now()});
            }
        }
        for (std::size_t i = 1; i < fds.size(); ++i) {
            if (!(fds[i].revents & (POLLIN | POLLHUP | POLLERR))) continue;
            auto it = std::find_if(clients.begin(), clients.end(), [&](const Connection& c) { return c.fd == fds[i].fd; });
            if (it == clients.end()) continue;
            char buffer[2048];
            const auto n = ::recv(it->fd, buffer, sizeof(buffer), 0);
            if (n == 0 || (n < 0 && errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR)) {
                close_connection(*it); clients.erase(it); continue;
            }
            if (n < 0) continue;
            it->data.append(buffer, static_cast<std::size_t>(n));
            if (it->data.size() > max_request_bytes) {
                respond(it->fd, 431, "Request Header Fields Too Large", "Request too large", false);
                close_connection(*it); clients.erase(it); continue;
            }
            if (it->data.find("\r\n\r\n") == std::string::npos) continue;

            // Request line: GET <origin-form target> HTTP/1.x
            const auto line_end = it->data.find("\r\n");
            const std::string_view line(it->data.data(), line_end);
            const auto first = line.find(' ');
            const auto last = line.rfind(' ');
            if (first == std::string_view::npos || last == first || line.substr(0, first) != "GET") {
                respond(it->fd, 405, "Method Not Allowed", "Method not allowed", false);
                close_connection(*it); clients.erase(it); continue;
            }
            const auto target = line.substr(first + 1, last - first - 1);
            const auto question = target.find('?');
            const auto path = target.substr(0, question);
            if (path != callback_path) {
                respond(it->fd, 404, "Not Found", "Not found", false);
                close_connection(*it); clients.erase(it); continue;
            }
            const auto params = callback_parameters(
                question == std::string_view::npos ? std::string_view{} : target.substr(question + 1));
            if (!params.state || !same_secret(*params.state, login.state)) {
                // Another app or an old browser tab. Keep waiting for the real one.
                diagnostic("browser login callback rejected reason=state");
                respond(it->fd, 400, "Bad Request",
                    "State mismatch. This sign-in link does not belong to the current Juggluco sign-in.", false);
                close_connection(*it); clients.erase(it); continue;
            }

            // The one valid callback: stop listening before doing anything else.
            Connection callback = std::move(*it);
            clients.erase(it);
            login.close_listener();
            for (auto& c : clients) close_connection(c);
            clients.clear();
            clients.push_back(std::move(callback));
            auto& client = clients.back();
            diagnostic("browser login callback accepted has_code=%d has_error=%d",
                params.code && !params.code->empty() ? 1 : 0, params.error ? 1 : 0);
            if (on_callback) on_callback();
            bool answered = false;
            try {
                if (params.error) {
                    std::string reason;
                    for (const char c : *params.error)
                        if (reason.size() < 64 && ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.'))
                            reason += c;
                    respond(client.fd, 200, "OK", result_page(false, return_link), true);
                    answered = true;
                    throw UiError(UiMessage::detail(UiCode::browser_sign_in_denied, reason));
                }
                if (!params.code || params.code->empty() || params.code->size() > token_limit) {
                    respond(client.fd, 200, "OK", result_page(false, return_link), true);
                    answered = true;
                    throw UiError(UiCode::browser_sign_in_missing_code);
                }
                auto exchange = request("/oauth/token", "grant_type=authorization_code&client_id=" +
                    form_escape(client_id) + "&code=" + form_escape(*params.code) + "&redirect_uri=" +
                    form_escape(login.redirect_uri) + "&code_verifier=" + form_escape(login.code_verifier), true);
                exchange.timeout = std::chrono::seconds(60);
                // One-time codes are never retried (see complete_device_login).
                const auto exchanged = send_request(http, exchange, cancel, true);
                std::fill(exchange.body.begin(), exchange.body.end(), '\0');
                if (!success(exchanged.status))
                    throw UiError({UiCode::browser_sign_in_rejected, {std::to_string(exchanged.status)}});
                auto tokens = token_response(parse_object(exchanged.body));
                respond(client.fd, 200, "OK", result_page(true, return_link), true);
                answered = true;
                close_connection(client);
                std::fill(login.code_verifier.begin(), login.code_verifier.end(), '\0');
                diagnostic("browser login token exchange succeeded");
                return tokens;
            } catch (...) {
                if (!answered) respond(client.fd, 200, "OK", result_page(false, return_link), true);
                close_connection(client);
                diagnostic("browser login callback failed");
                throw;
            }
        }
    }
}

AccountInfo account_info(const Tokens& tokens) noexcept {
    AccountInfo out;
    auto display = [](const Json& object, const char* name, std::size_t limit) -> std::string {
        const auto it = object.find(name);
        if (it == object.end() || !it->is_string()) return {};
        auto value = it->get<std::string>();
        if (value.empty() || value.size() > limit) return {};
        for (const unsigned char c : value) if (c < 32 || c == 127) return {};
        return value;
    };
    for (const std::string* token : {&tokens.id_token, &tokens.access_token}) {
        try {
            const std::string_view jwt(*token);
            const auto first = jwt.find('.');
            const auto second = first == std::string_view::npos ? first : jwt.find('.', first + 1);
            if (second == std::string_view::npos) continue;
            const auto claims = parse_object(decode_base64url(jwt.substr(first + 1, second - first - 1)), token_limit);
            if (out.email.empty()) out.email = display(claims, "email", 320);
            const auto auth = claims.find("https://api.openai.com/auth");
            if (out.plan.empty() && auth != claims.end() && auth->is_object())
                out.plan = display(*auth, "chatgpt_plan_type", 64);
            if (out.email.empty()) {
                const auto profile = claims.find("https://api.openai.com/profile");
                if (profile != claims.end() && profile->is_object()) out.email = display(*profile, "email", 320);
            }
        } catch (...) {}
    }
    return out;
}

} // namespace jgchat
