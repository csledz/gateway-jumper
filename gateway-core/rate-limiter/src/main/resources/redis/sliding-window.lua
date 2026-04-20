-- SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
-- SPDX-License-Identifier: Apache-2.0
--
-- Atomic sliding-window counter backed by a Redis sorted set.
--
-- KEYS[1] = counter key (ZSET)
-- ARGV[1] = window size in milliseconds
-- ARGV[2] = limit (integer)
-- ARGV[3] = burst allowance (integer, may be 0)
-- ARGV[4] = nowMillis (long, server-provided; avoids clock skew)
-- ARGV[5] = requestId (unique suffix so concurrent calls in the same ms don't collide)
--
-- Returns a 4-element table:
--   { allowed (1|0), remaining, resetAtEpochMs, retryAfterMs }

local key        = KEYS[1]
local windowMs   = tonumber(ARGV[1])
local limit      = tonumber(ARGV[2])
local burst      = tonumber(ARGV[3])
local now        = tonumber(ARGV[4])
local requestId  = ARGV[5]

local cap = limit + burst
local cutoff = now - windowMs

-- drop expired entries
redis.call('ZREMRANGEBYSCORE', key, '-inf', cutoff)

local count = tonumber(redis.call('ZCARD', key))

if count < cap then
    redis.call('ZADD', key, now, now .. '-' .. requestId)
    redis.call('PEXPIRE', key, windowMs)
    local remaining = cap - (count + 1)
    return { 1, remaining, now + windowMs, 0 }
end

-- rejected: compute retryAfter based on oldest entry
local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
local retryAfterMs = 0
if oldest and oldest[2] then
    retryAfterMs = math.max(0, (tonumber(oldest[2]) + windowMs) - now)
end
redis.call('PEXPIRE', key, windowMs)
return { 0, 0, now + retryAfterMs, retryAfterMs }
