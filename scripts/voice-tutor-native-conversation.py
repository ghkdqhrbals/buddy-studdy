#!/usr/bin/env python3
"""Opt-in host fixture for a recv-only iPhone's three-turn native RTP test.

Run only after coordinating with the device-test owner. This creates at most one
standalone provider call, never a BuddyStudy session. No microphone, quota API,
transcript/SDP logging, recording files, Docker changes, or routing changes.
Requires --run, OPENAI_API_KEY, and a fresh random
BUDDYSTUDY_NATIVE_CONVERSATION_TOKEN supplied through the process environment.
There is no credential discovery or dependency on ignored build helpers.
The provider credential stays in this process; the phone receives only the SDP
answer and a bounded allowlist of ordinal-only lifecycle events.
See scripts/voice-tutor-native-conversation.md before running; never run in CI.
"""

import argparse
import asyncio
import collections
import contextlib
import hmac
import ipaddress
import json
import logging
import os
import re
import signal
import time

import aiohttp
from aiohttp import web


CALLS_API = "https://api.openai.com/v1/realtime/calls"
MAX_SECONDS = 60
NEGOTIATION_TIMEOUT_SECONDS = 20
HANGUP_TIMEOUT_SECONDS = 5
HOST_IDLE_TIMEOUT_SECONDS = 300
MAX_EVENTS = 96
MAX_SDP_BYTES = 128 * 1024
TEACHER_LINES = (
    "안녕하세요, 저는 학습을 도와드리는 선생님이고 준비가 되면 레디스의 캐시 개념부터 함께 살펴보겠습니다.",
    "캐시는 자주 사용하는 데이터를 가까운 곳에 임시로 보관해서 같은 요청에 더 빠르게 답하도록 도와주는 저장 공간입니다.",
    "레디스는 자주 조회하는 데이터의 캐시나 짧게 유지하는 세션 정보를 빠르게 저장하고 읽을 때 사용할 수 있습니다.",
)
LEARNER_LINES = (
    "네, 학습을 시작할게요. 캐시가 무엇인지 한 문장으로 설명해 주세요.",
    "이제 이해했어요. 레디스를 언제 사용하는지 한 문장으로 알려주세요.",
)


class ProbeFailure(Exception):
    """Only fixed, nonsensitive reason codes may be passed here."""


class NativeConversationHost:
    def __init__(self):
        self.http = None
        self.ws = None
        self.call_id = None
        self.offer_used = False
        self.start_used = False
        self.closing = False
        self.terminal = False
        self.completed = False
        self.failed = False
        self.cleanup_failed = False
        self.allocation_attempted = False
        self.hangup_confirmed = False
        self.events = []
        self.changed = asyncio.Event()
        self.stop_requested = asyncio.Event()
        self.offer_claimed = asyncio.Event()
        self.tasks = set()
        self.call_started = None
        self.negotiation_deadline = None
        self.negotiation_task = None
        self.shutdown_task = None
        self.active_turn = 0
        self.response_ordinals = {}
        self.done_turns = set()
        self.stopped_turns = set()
        self.accepted_inputs = set()
        self.scheduled_inputs = set()
        self.scheduled_responses = set()
        self.counts = collections.Counter()

    @property
    def stopping(self):
        return self.closing or self.stop_requested.is_set()

    @property
    def exit_code(self):
        # A provider timeout without a Location is not evidence that no paid
        # call was allocated. Only completed turns AND acknowledged cleanup
        # constitute a passing host run.
        return int(not (self.completed and self.hangup_confirmed
                        and not self.failed and not self.cleanup_failed))

    def request_stop(self):
        self.stop_requested.set()

    def spawn(self, coroutine):
        if self.stopping:
            coroutine.close()
            return None

        async def guarded():
            try:
                await coroutine
            except asyncio.CancelledError:
                raise
            except ProbeFailure as error:
                await self.fail(str(error))
            except Exception:
                await self.fail("background_fixture_failed")
        task = asyncio.create_task(guarded())
        self.tasks.add(task)
        task.add_done_callback(self.tasks.discard)
        return task

    def publish(self, kind, turn=0, **fields):
        if len(self.events) >= MAX_EVENTS:
            raise ProbeFailure("event_budget_exceeded")
        item = {"sequence": len(self.events) + 1, "kind": kind, "turn": turn}
        item.update(fields)
        self.events.append(item)
        self.changed.set()

    async def fail(self, reason):
        if not self.failed:
            self.failed = True
            self.terminal = True
            if len(self.events) < MAX_EVENTS:
                self.publish("probe_failed", reason=reason)
            print(json.dumps({"event": "probe_failed", "reason": reason}), flush=True)
        self.request_stop()

    async def offer(self, request):
        if self.stopping or self.terminal:
            return web.json_response({"error": "host_stopping"}, status=503)
        if self.offer_used:
            return web.json_response({"error": "single_call_only"}, status=409)
        raw = await request.read()
        if not raw or len(raw) > MAX_SDP_BYTES:
            return web.json_response({"error": "invalid_offer_size"}, status=400)
        try:
            offer = raw.decode("utf-8")
        except UnicodeDecodeError:
            return web.json_response({"error": "invalid_offer_encoding"}, status=400)
        media = [line for line in offer.splitlines() if line.startswith("m=")]
        if len(media) != 1 or not media[0].startswith("m=audio ") or "a=recvonly" not in offer:
            return web.json_response({"error": "recvonly_audio_required"}, status=400)
        if "a=sendrecv" in offer or "a=sendonly" in offer:
            return web.json_response({"error": "local_audio_forbidden"}, status=400)
        # Request-body reads yield to other handlers. Recheck immediately
        # before this non-awaiting claim so concurrent offers cannot allocate
        # a second paid call with the same capability.
        if self.stopping or self.terminal:
            return web.json_response({"error": "host_stopping"}, status=503)
        if self.offer_used:
            return web.json_response({"error": "single_call_only"}, status=409)
        self.offer_used = True
        self.call_started = time.monotonic()
        self.negotiation_deadline = self.call_started + NEGOTIATION_TIMEOUT_SECONDS
        self.offer_claimed.set()
        self.spawn(self.deadline())
        # The host, not the HTTP request, owns allocation. A disconnected phone
        # must not cancel the only opportunity to recover the provider call ID.
        self.negotiation_task = asyncio.create_task(self.negotiate(offer))
        try:
            answer = await asyncio.shield(self.negotiation_task)
        except asyncio.CancelledError:
            self.request_stop()
            raise
        if self.stopping or answer is None:
            return web.json_response({"error": "host_stopping"}, status=503)
        self.publish("offer_answered")
        return web.Response(body=answer, content_type="application/sdp")

    async def negotiate(self, offer):
        if self.stopping:
            return None
        config = {
            "type": "realtime", "model": "gpt-realtime-2.1",
            "output_modalities": ["audio"], "max_output_tokens": 256,
            "instructions": "This is a synthetic three-turn Korean tutor verification. Speak only the requested complete sentence. Never call tools or end the call yourself.",
            "audio": {"input": {"turn_detection": None}, "output": {"voice": "marin"}},
        }
        data = aiohttp.FormData()
        data.add_field("sdp", offer, content_type="application/sdp")
        data.add_field("session", json.dumps(config), content_type="application/json")
        self.allocation_attempted = True
        print(json.dumps({"event": "provider_negotiation_started"}), flush=True)
        remaining = max(0, self.negotiation_deadline - time.monotonic())
        async with asyncio.timeout(remaining):
            async with self.http.post(CALLS_API, data=data) as response:
                if response.status not in (200, 201):
                    raise ProbeFailure("provider_negotiation_failed")
                location = response.headers.get("Location", "")
                call_id = location.rsplit("/", 1)[-1]
                if not re.fullmatch(r"rtc_[A-Za-z0-9_-]{1,187}", call_id):
                    raise ProbeFailure("invalid_provider_call_identity")
                self.call_id = call_id
                print(json.dumps({"event": "provider_call_created"}), flush=True)
                if self.stopping:
                    return None
                # Bound the read itself, not just an already allocated body.
                try:
                    answer = await response.content.readexactly(MAX_SDP_BYTES + 1)
                except asyncio.IncompleteReadError as error:
                    answer = error.partial
                if not answer or len(answer) > MAX_SDP_BYTES:
                    raise ProbeFailure("invalid_answer_size")
                return answer

    async def start(self, request):
        if self.stopping or self.terminal:
            return web.json_response({"error": "host_stopping"}, status=503)
        if not self.call_id or self.start_used:
            return web.json_response({"error": "invalid_start_state"}, status=409)
        self.start_used = True
        self.spawn(self.conversation())
        return web.json_response({"started": True})

    async def poll(self, request):
        raw = request.query.get("after", "0")
        if not raw.isascii() or not raw.isdigit() or len(raw) > 3:
            return web.json_response({"error": "invalid_cursor"}, status=400)
        after = int(raw)
        if after > len(self.events):
            return web.json_response({"error": "invalid_cursor"}, status=400)
        if after == len(self.events) and not self.terminal:
            self.changed.clear()
            with contextlib.suppress(TimeoutError):
                await asyncio.wait_for(self.changed.wait(), 0.25)
        return web.json_response({"events": self.events[after:], "terminal": self.terminal})

    async def close_request(self, request):
        self.request_stop()
        return web.json_response({"closing": True})

    async def deadline(self):
        # Reserve the final five seconds for the bounded hangup request.
        await asyncio.sleep(max(0, self.call_started + MAX_SECONDS
                                - HANGUP_TIMEOUT_SECONDS - time.monotonic()))
        await self.fail("provider_call_deadline")

    async def wait_for_stop(self, idle_timeout=HOST_IDLE_TIMEOUT_SECONDS):
        # An unused host expires after five minutes. Once a call is claimed,
        # only its own absolute watchdog applies, even if it starts near 5:00.
        stop = asyncio.create_task(self.stop_requested.wait())
        claimed = asyncio.create_task(self.offer_claimed.wait())
        try:
            done, _ = await asyncio.wait((stop, claimed), timeout=idle_timeout,
                                         return_when=asyncio.FIRST_COMPLETED)
            if not done and not self.offer_claimed.is_set() and not self.stopping:
                await self.fail("host_idle_deadline")
            await self.stop_requested.wait()
        finally:
            stop.cancel()
            claimed.cancel()
            await asyncio.gather(stop, claimed, return_exceptions=True)

    async def request_response(self, turn):
        if self.stopping or self.terminal:
            return
        if turn in self.scheduled_responses or turn not in (1, 2, 3):
            raise ProbeFailure("unexpected_response_request")
        self.scheduled_responses.add(turn)
        self.active_turn = turn
        await self.ws.send_json({
            "type": "response.create",
            "response": {"instructions": "Say exactly this one Korean sentence, without any additions: " + TEACHER_LINES[turn - 1]},
        })

    async def synthetic_input(self, ordinal):
        # Deliberately overlap the provider's current output. This is a fixed
        # synthetic text turn, not ASR/microphone verification or a fake ACK.
        await asyncio.sleep(0.2)
        if self.stopping or self.terminal:
            return
        await self.ws.send_json({
            "type": "conversation.item.create",
            "item": {"id": "item_native_probe_learner_" + str(ordinal),
                     "type": "message", "role": "user",
                     "content": [{"type": "input_text", "text": LEARNER_LINES[ordinal - 1]}]},
        })
        self.publish("synthetic_input_sent", ordinal)

    async def advance(self, completed_turn):
        # Observe post-response noise after R1/R3, but R2 -> R3 follows the
        # production gate immediately, without waiting for client PCM/drain.
        # Neither path polls silence or sends a fabricated client ACK.
        if completed_turn != 2:
            await asyncio.sleep(1.0)
        if self.stopping or self.terminal:
            return
        if completed_turn < 3:
            await self.request_response(completed_turn + 1)
        else:
            self.completed = True
            self.terminal = True
            self.publish("probe_complete", responses=3, acceptedInputs=len(self.accepted_inputs),
                         providerClears=self.counts["cleared"], providerTruncations=self.counts["truncated"],
                         providerErrors=self.counts["error"], clientDrainMessages=0)

    async def maybe_advance(self):
        turn = self.active_turn
        if turn not in self.done_turns or turn not in self.stopped_turns:
            return
        if turn < 3 and turn not in self.accepted_inputs:
            return
        key = "advanced_" + str(turn)
        if self.counts[key]:
            return
        self.counts[key] += 1
        self.spawn(self.advance(turn))

    async def conversation(self):
        try:
            if self.stopping:
                return
            self.ws = await self.http.ws_connect(
                "wss://api.openai.com/v1/realtime?call_id=" + self.call_id,
                max_msg_size=1024 * 1024,
            )
            if self.stopping:
                return
            await self.ws.send_json({"type": "session.update", "session": {
                "type": "realtime", "audio": {"input": {"turn_detection": None}},
            }})
            confirmed_manual = False
            async for message in self.ws:
                if message.type != aiohttp.WSMsgType.TEXT:
                    raise ProbeFailure("provider_sideband_closed")
                event = json.loads(message.data)
                kind = event.get("type")
                if kind == "session.updated" and not confirmed_manual:
                    audio_input = event.get("session", {}).get("audio", {}).get("input", {})
                    if "turn_detection" not in audio_input or audio_input["turn_detection"] is not None:
                        raise ProbeFailure("manual_turn_detection_not_confirmed")
                    confirmed_manual = True
                    self.publish("manual_session_ready")
                    await self.request_response(1)
                elif kind == "response.created":
                    response_id = event.get("response", {}).get("id")
                    if not isinstance(response_id, str) or not 1 <= self.active_turn <= 3:
                        raise ProbeFailure("unrequested_response")
                    if self.active_turn in self.response_ordinals.values():
                        raise ProbeFailure("duplicate_response")
                    self.response_ordinals[response_id] = self.active_turn
                    self.publish("response_started", self.active_turn)
                elif kind in ("response.done", "output_audio_buffer.started", "output_audio_buffer.stopped"):
                    response_id = (event.get("response") or {}).get("id") if kind == "response.done" else event.get("response_id")
                    turn = self.response_ordinals.get(response_id)
                    if turn is None:
                        raise ProbeFailure("unmatched_provider_lifecycle")
                    if kind == "response.done":
                        if event.get("response", {}).get("status") != "completed":
                            raise ProbeFailure("provider_response_not_completed")
                        self.done_turns.add(turn)
                        self.publish("response_done", turn)
                    elif kind == "output_audio_buffer.started":
                        self.publish("output_started", turn)
                        if turn < 3 and turn not in self.scheduled_inputs:
                            self.scheduled_inputs.add(turn)
                            self.spawn(self.synthetic_input(turn))
                    else:
                        self.stopped_turns.add(turn)
                        self.publish("output_stopped", turn)
                    await self.maybe_advance()
                elif kind in ("conversation.item.done", "conversation.item.created", "conversation.item.added"):
                    item = event.get("item") or {}
                    match = re.fullmatch(r"item_native_probe_learner_([12])", str(item.get("id", "")))
                    accepted = kind != "conversation.item.added" or item.get("status") == "completed"
                    if match and accepted:
                        ordinal = int(match.group(1))
                        if ordinal not in self.accepted_inputs:
                            self.accepted_inputs.add(ordinal)
                            self.publish("synthetic_input_accepted", ordinal)
                            await self.maybe_advance()
                elif kind in ("output_audio_buffer.cleared", "conversation.item.truncated", "error"):
                    counter = {"output_audio_buffer.cleared": "cleared", "conversation.item.truncated": "truncated", "error": "error"}[kind]
                    self.counts[counter] += 1
                    raise ProbeFailure("forbidden_provider_" + counter)
        except asyncio.CancelledError:
            raise
        except ProbeFailure as error:
            await self.fail(str(error))
        except Exception:
            await self.fail("provider_transport_failure")
        finally:
            if not self.stopping and not self.terminal:
                await self.fail("provider_sideband_ended_early")

    async def shutdown(self):
        self.request_stop()
        self.closing = True
        # All callers join the same cleanup, including a canceled offer and a
        # simultaneous signal. Do not return while another caller is hanging up.
        if self.shutdown_task is None:
            self.shutdown_task = asyncio.create_task(self.finish_shutdown())
        await asyncio.shield(self.shutdown_task)

    async def recover_negotiation(self):
        task = self.negotiation_task
        if task is None:
            return
        if not task.done():
            if self.call_id:
                # Location is already captured; abandon a slow SDP body so we
                # can use the ID for hangup immediately.
                task.cancel()
            else:
                remaining = max(0, min(self.negotiation_deadline,
                                       self.call_started + MAX_SECONDS
                                       - HANGUP_TIMEOUT_SECONDS) - time.monotonic())
                try:
                    async with asyncio.timeout(remaining):
                        await asyncio.shield(task)
                except TimeoutError:
                    task.cancel()
                except Exception:
                    pass  # fixed cleanup outcome below, never raw transport data
        with contextlib.suppress(asyncio.CancelledError, Exception):
            await task

    async def finish_shutdown(self):
        for task in tuple(self.tasks):
            task.cancel()
        if self.tasks:
            await asyncio.gather(*tuple(self.tasks), return_exceptions=True)
        try:
            await self.recover_negotiation()
            if self.call_id:
                remaining = max(0, min(HANGUP_TIMEOUT_SECONDS,
                                       self.call_started + MAX_SECONDS - time.monotonic()))
                try:
                    async with asyncio.timeout(remaining):
                        async with self.http.post(
                            CALLS_API + "/" + self.call_id + "/hangup",
                            timeout=aiohttp.ClientTimeout(total=remaining),
                        ) as response:
                            self.hangup_confirmed = 200 <= response.status < 300
                            print(json.dumps({"event": "provider_hangup", "status": response.status}), flush=True)
                except Exception:
                    print(json.dumps({"event": "provider_hangup_failed"}), flush=True)
        finally:
            # A failed websocket close must not skip closing the credential-
            # holding HTTP session. Each local close has its own short bound.
            for resource in (self.ws, self.http):
                if resource is not None:
                    try:
                        async with asyncio.timeout(1):
                            await resource.close()
                    except Exception:
                        self.cleanup_failed = True
                        print(json.dumps({"event": "local_transport_close_failed"}), flush=True)
            cleanup = ("confirmed" if self.hangup_confirmed else
                       "unconfirmed" if self.allocation_attempted else "not_attempted")
            if cleanup == "unconfirmed":
                print(json.dumps({"event": "provider_cleanup_unconfirmed",
                                  "callIdentityKnown": self.call_id is not None}), flush=True)
            print(json.dumps({"event": "host_stopped", "probeCompleted": self.completed,
                              "providerCleanup": cleanup, "exitCode": self.exit_code}), flush=True)


async def serve(args):
    token = os.environ.get("BUDDYSTUDY_NATIVE_CONVERSATION_TOKEN", "")
    if not re.fullmatch(r"[A-Za-z0-9_-]{32,128}", token):
        raise ProbeFailure("test_capability_token_required")
    key = os.environ.get("OPENAI_API_KEY", "")
    if not key or not key.isascii() or any(character.isspace() for character in key):
        raise ProbeFailure("openai_api_key_required")
    probe = NativeConversationHost()
    probe.http = aiohttp.ClientSession(
        headers={"Authorization": "Bearer " + key},
        timeout=aiohttp.ClientTimeout(total=NEGOTIATION_TIMEOUT_SECONDS),
    )

    @web.middleware
    async def authorized(request, handler):
        expected = "Bearer " + token
        if not hmac.compare_digest(request.headers.get("Authorization", ""), expected):
            return web.json_response({"error": "unauthorized"}, status=401)
        if request.path in ("/offer", "/start", "/close"):
            print(json.dumps({"event": "fixture_request_received", "route": request.path}), flush=True)
        try:
            return await handler(request)
        except asyncio.CancelledError:
            raise
        except ProbeFailure as error:
            await probe.fail(str(error))
        except Exception:
            await probe.fail("host_request_failed")
        return web.json_response({"error": "probe_failed"}, status=502)

    app = web.Application(middlewares=[authorized], client_max_size=MAX_SDP_BYTES)
    app.router.add_post("/offer", probe.offer)
    app.router.add_post("/start", probe.start)
    app.router.add_get("/events", probe.poll)
    app.router.add_post("/close", probe.close_request)
    runner = web.AppRunner(app, access_log=None, shutdown_timeout=1)
    loop = asyncio.get_running_loop()
    # A background shell job may inherit SIGINT ignored. Install both handlers
    # explicitly so cancellation still runs the bounded provider-hangup cleanup.
    installed_signals = []
    try:
        for stop_signal in (signal.SIGINT, signal.SIGTERM):
            loop.add_signal_handler(stop_signal, probe.request_stop)
            installed_signals.append(stop_signal)
        await runner.setup()
        await web.TCPSite(runner, args.bind, args.port).start()
        print(json.dumps({"event": "host_ready", "port": args.port, "maxCalls": 1,
                          "callDeadlineSeconds": MAX_SECONDS, "maxResponses": 3}), flush=True)
        await probe.wait_for_stop()
        await asyncio.sleep(0.2)  # allow the final authenticated HTTP reply to flush
    finally:
        try:
            await probe.shutdown()
        finally:
            try:
                await runner.cleanup()
            finally:
                for stop_signal in installed_signals:
                    loop.remove_signal_handler(stop_signal)
    return probe.exit_code


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run", action="store_true", help="Explicitly arm one paid standalone call")
    parser.add_argument("--bind", default="127.0.0.1",
                        help="Explicit private/loopback IPv4 address; no wildcard bind")
    parser.add_argument("--port", type=int, default=8097)
    args = parser.parse_args()
    if not args.run:
        parser.error("--run is required; no credential or provider call has been accessed")
    try:
        bind_address = ipaddress.IPv4Address(args.bind)
    except ipaddress.AddressValueError:
        parser.error("--bind must be one explicit private or loopback IPv4 address")
    if (bind_address.is_unspecified or bind_address.is_multicast
            or not (bind_address.is_private or bind_address.is_loopback)):
        parser.error("--bind must be one explicit private or loopback IPv4 address")
    if not 1024 <= args.port <= 65535 or args.port == 8080:
        parser.error("Choose a test-only unprivileged port other than the existing backend's 8080")
    logging.getLogger("aiohttp").setLevel(logging.CRITICAL)
    try:
        raise SystemExit(asyncio.run(serve(args)))
    except KeyboardInterrupt:
        raise SystemExit(1)
    except ProbeFailure as error:
        print(json.dumps({"event": "host_failed", "reason": str(error),
                          "detailsRedacted": True}), flush=True)
        raise SystemExit(1)
    except Exception:
        print(json.dumps({"event": "host_failed", "detailsRedacted": True}), flush=True)
        raise SystemExit(1)
