#pragma once
// JNI/Java contract: only these two positive receipts permit pending cleanup.
// Rejected includes uncertain writes; keep durable consent + payload and retry.
enum class MeterImportReceipt : int { Rejected=0, Persisted=1, AlreadyPresent=2 };
