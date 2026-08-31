"""No-network regression tests for the explicitly opt-in native probe host.

Run with Python 3.11+ and aiohttp installed. Every HTTP/websocket operation is
fake; real ClientSession construction and server startup are forbidden.
"""

import asyncio
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import runpy
import time
import unittest
from unittest import mock


SCRIPT = Path(__file__).resolve().parents[1] / "voice-tutor-native-conversation.py"
SPEC = importlib.util.spec_from_file_location("native_conversation_fixture", SCRIPT)
fixture = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(fixture)

TEST_CALL_ID = "rtc_fixture_private_identity"
TEST_KEY = "sk-test-not-a-real-provider-key"
TEST_TOKEN = "test-only-capability-never-use-live-12345"
OFFER = (b"v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=recvonly\r\n"
         b"a=ice-pwd:private-fixture-offer\r\n")
ANSWER = b"v=0\r\na=ice-pwd:private-fixture-answer\r\n"


class FakeRequest:
    def __init__(self, body=OFFER, block_body=False):
        self.body = body
        self.read_started = asyncio.Event()
        self.allow_read = asyncio.Event()
        if not block_body:
            self.allow_read.set()

    async def read(self):
        self.read_started.set()
        await self.allow_read.wait()
        return self.body


class FakeContent:
    def __init__(self, http):
        self.http = http

    async def readexactly(self, size):
        self.http.body_started.set()
        self.http.read_sizes.append(size)
        await self.http.allow_body.wait()
        if len(self.http.answer) < size:
            raise asyncio.IncompleteReadError(self.http.answer, size)
        return self.http.answer[:size]


class FakeResponse:
    def __init__(self, http, hangup):
        self.status = http.hangup_status if hangup else http.allocation_status
        self.headers = {} if hangup else {"Location": fixture.CALLS_API + "/" + TEST_CALL_ID}
        self.content = FakeContent(http)


class FakePost:
    def __init__(self, http, hangup):
        self.http = http
        self.hangup = hangup

    async def __aenter__(self):
        if self.http.closed:
            raise AssertionError("HTTP closed before owned allocation/cleanup settled")
        if self.hangup:
            self.http.hangup_started.set()
            await self.http.allow_hangup.wait()
            if self.http.hangup_error:
                raise self.http.hangup_error
            self.http.order.append("hangup_ack")
        else:
            self.http.allocation_started.set()
            await self.http.allow_headers.wait()
            if self.http.allocation_error:
                raise self.http.allocation_error
            self.http.order.append("allocation_headers")
        return FakeResponse(self.http, self.hangup)

    async def __aexit__(self, *ignored):
        self.http.order.append("hangup_context_closed" if self.hangup else "allocation_context_closed")


class FakeHTTP:
    def __init__(self):
        self.closed = False
        self.order = []
        self.posts = []
        self.read_sizes = []
        self.answer = ANSWER
        self.allocation_status = 201
        self.hangup_status = 200
        self.allocation_error = None
        self.hangup_error = None
        self.allocation_started = asyncio.Event()
        self.body_started = asyncio.Event()
        self.hangup_started = asyncio.Event()
        self.allow_headers = asyncio.Event()
        self.allow_body = asyncio.Event()
        self.allow_hangup = asyncio.Event()
        self.allow_headers.set()
        self.allow_body.set()
        self.allow_hangup.set()

    def post(self, url, **kwargs):
        if url not in (fixture.CALLS_API, fixture.CALLS_API + "/" + TEST_CALL_ID + "/hangup"):
            raise AssertionError("Unexpected endpoint")
        hangup = url.endswith("/hangup")
        self.posts.append("hangup" if hangup else "allocation")
        if hangup:
            if not 0 <= kwargs["timeout"].total <= fixture.HANGUP_TIMEOUT_SECONDS:
                raise AssertionError("Hangup exceeds its remaining budget")
        return FakePost(self, hangup)

    async def ws_connect(self, *args, **kwargs):
        raise AssertionError("No test may open a websocket")

    async def close(self):
        self.closed = True
        self.order.append("http_closed")


class FakeBrokenWebSocket:
    def __init__(self):
        self.close_attempted = False

    async def close(self):
        self.close_attempted = True
        raise RuntimeError(TEST_KEY + TEST_TOKEN + ANSWER.decode())


class NativeConversationHostTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.output = io.StringIO()
        self.stdout = contextlib.redirect_stdout(self.output)
        self.stdout.__enter__()
        self.no_client = mock.patch.object(
            fixture.aiohttp, "ClientSession", side_effect=AssertionError("Real HTTP forbidden"),
        )
        self.client_constructor = self.no_client.start()
        self.no_server = mock.patch.object(
            fixture.web.TCPSite, "start", side_effect=AssertionError("Real listener forbidden"),
        )
        self.no_server.start()
        self.host = fixture.NativeConversationHost()
        self.http = FakeHTTP()
        self.host.http = self.http
        self.owned_test_tasks = []

    async def asyncTearDown(self):
        # Even a failed assertion releases all fake waits, without touching a
        # socket, external credential, device, app session or filesystem data.
        self.http.allow_headers.set()
        self.http.allow_body.set()
        self.http.allow_hangup.set()
        try:
            await asyncio.wait_for(self.host.shutdown(), 1)
            for task in self.owned_test_tasks:
                if not task.done():
                    task.cancel()
            await asyncio.gather(*self.owned_test_tasks, return_exceptions=True)
            self.client_constructor.assert_not_called()
        finally:
            self.no_server.stop()
            self.no_client.stop()
            self.stdout.__exit__(None, None, None)

    def task(self, coroutine):
        task = asyncio.create_task(coroutine)
        self.owned_test_tasks.append(task)
        return task

    async def entered(self, event):
        await asyncio.wait_for(event.wait(), 0.5)

    def metadata(self):
        return [json.loads(line) for line in self.output.getvalue().splitlines() if line]

    async def test_stop_rejects_offer_and_start_without_allocating(self):
        await self.host.close_request(None)
        self.assertEqual((await self.host.offer(FakeRequest())).status, 503)
        self.assertEqual((await self.host.start(None)).status, 503)
        self.assertEqual(self.http.posts, [])
        self.assertFalse(self.host.offer_used)
        self.assertFalse(self.host.start_used)

    async def test_stop_during_request_body_is_rechecked_before_claim(self):
        request = FakeRequest(block_body=True)
        offer = self.task(self.host.offer(request))
        await self.entered(request.read_started)
        self.host.request_stop()
        request.allow_read.set()
        self.assertEqual((await offer).status, 503)
        self.assertEqual(self.http.posts, [])

    async def test_concurrent_offers_claim_only_one_paid_allocation(self):
        requests = [FakeRequest(block_body=True), FakeRequest(block_body=True)]
        offers = [self.task(self.host.offer(request)) for request in requests]
        for request in requests:
            await self.entered(request.read_started)
            request.allow_read.set()
        responses = await asyncio.gather(*offers)
        self.assertEqual(sorted(response.status for response in responses), [200, 409])
        self.assertEqual(self.http.posts, ["allocation"])
        self.assertEqual(self.http.read_sizes, [fixture.MAX_SDP_BYTES + 1])

    async def test_shutdown_recovers_delayed_location_before_hangup_and_http_close(self):
        self.http.allow_headers.clear()
        offer = self.task(self.host.offer(FakeRequest()))
        await self.entered(self.http.allocation_started)
        shutdown = self.task(self.host.shutdown())
        await asyncio.sleep(0)
        self.assertFalse(shutdown.done())
        self.assertFalse(self.http.closed)
        self.http.allow_headers.set()
        self.assertEqual((await offer).status, 503)
        await shutdown
        self.assertEqual(self.host.call_id, TEST_CALL_ID)
        self.assertEqual(self.http.posts, ["allocation", "hangup"])
        self.assertLess(self.http.order.index("hangup_ack"), self.http.order.index("http_closed"))
        self.assertEqual(self.http.read_sizes, [])
        self.assertTrue(self.host.hangup_confirmed)

    async def test_canceled_offer_does_not_cancel_owned_allocation(self):
        self.http.allow_headers.clear()
        offer = self.task(self.host.offer(FakeRequest()))
        await self.entered(self.http.allocation_started)
        offer.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await offer
        self.assertTrue(self.host.stopping)
        self.assertFalse(self.host.negotiation_task.done())
        shutdown = self.task(self.host.shutdown())
        self.http.allow_headers.set()
        await shutdown
        self.assertTrue(self.host.hangup_confirmed)
        self.assertEqual(self.http.posts, ["allocation", "hangup"])

    async def test_location_known_shutdown_cancels_slow_answer_body(self):
        self.http.allow_body.clear()
        offer = self.task(self.host.offer(FakeRequest()))
        await self.entered(self.http.body_started)
        self.assertEqual(self.host.call_id, TEST_CALL_ID)
        await asyncio.wait_for(self.host.shutdown(), 0.5)
        with self.assertRaises(asyncio.CancelledError):
            await offer
        self.assertTrue(self.host.negotiation_task.cancelled())
        self.assertTrue(self.host.hangup_confirmed)
        self.assertFalse(self.http.allow_body.is_set())

    async def test_negotiation_timeout_without_identity_is_unconfirmed_and_nonzero(self):
        self.http.allow_headers.clear()
        with mock.patch.object(fixture, "NEGOTIATION_TIMEOUT_SECONDS", 0.02):
            with self.assertRaises(TimeoutError):
                await asyncio.wait_for(self.host.offer(FakeRequest()), 0.5)
        await self.host.shutdown()
        self.assertTrue(self.host.negotiation_task.done())
        self.assertIsNone(self.host.call_id)
        self.assertEqual(self.http.posts, ["allocation"])
        self.assertEqual(self.host.exit_code, 1)
        self.assertIn({"event": "provider_cleanup_unconfirmed", "callIdentityKnown": False}, self.metadata())
        self.assertEqual(self.metadata()[-1]["providerCleanup"], "unconfirmed")

    async def test_shutdown_preserves_original_negotiation_deadline(self):
        self.http.allow_headers.clear()
        offer = self.task(self.host.offer(FakeRequest()))
        await self.entered(self.http.allocation_started)
        # The in-flight request's remaining budget, not a new twenty seconds,
        # controls recovery. No timing-sensitive provider/network mock is used.
        self.host.negotiation_deadline = time.monotonic() + 0.02
        await asyncio.wait_for(self.host.shutdown(), 0.5)
        with self.assertRaises(asyncio.CancelledError):
            await offer
        self.assertTrue(self.host.negotiation_task.done())
        self.assertTrue(self.http.closed)
        self.assertEqual(self.host.exit_code, 1)

    async def test_repeated_shutdown_joins_one_hangup_instead_of_returning_early(self):
        await self.host.offer(FakeRequest())
        self.http.allow_hangup.clear()
        first = self.task(self.host.shutdown())
        await self.entered(self.http.hangup_started)
        second = self.task(self.host.shutdown())
        await asyncio.sleep(0)
        self.assertFalse(first.done())
        self.assertFalse(second.done())
        self.http.allow_hangup.set()
        await asyncio.gather(first, second)
        await self.host.shutdown()
        self.assertEqual(self.http.posts.count("hangup"), 1)
        self.assertEqual(sum(item["event"] == "host_stopped" for item in self.metadata()), 1)

    async def test_hangup_and_ws_close_failures_still_close_http_and_redact(self):
        await self.host.offer(FakeRequest())
        self.host.completed = True
        self.host.ws = FakeBrokenWebSocket()
        self.http.hangup_error = RuntimeError(TEST_KEY + TEST_TOKEN + TEST_CALL_ID)
        await self.host.shutdown()
        self.assertTrue(self.host.ws.close_attempted)
        self.assertTrue(self.http.closed)
        self.assertTrue(self.host.cleanup_failed)
        self.assertFalse(self.host.hangup_confirmed)
        self.assertEqual(self.host.exit_code, 1)
        for private_value in (TEST_KEY, TEST_TOKEN, TEST_CALL_ID, "private-fixture-offer", "private-fixture-answer"):
            self.assertNotIn(private_value, self.output.getvalue())
        self.assertEqual(self.metadata()[-1]["providerCleanup"], "unconfirmed")

    async def test_non_success_hangup_status_is_not_a_passing_run(self):
        await self.host.offer(FakeRequest())
        self.host.completed = True
        self.http.hangup_status = 503
        await self.host.shutdown()
        self.assertFalse(self.host.hangup_confirmed)
        self.assertEqual(self.host.exit_code, 1)

    async def test_completed_probe_requires_acknowledged_cleanup_for_exit_zero(self):
        await self.host.offer(FakeRequest())
        self.host.completed = True
        self.assertEqual(self.host.exit_code, 1)
        await self.host.shutdown()
        self.assertEqual(self.host.exit_code, 0)
        self.assertEqual(self.metadata()[-1], {
            "event": "host_stopped", "probeCompleted": True,
            "providerCleanup": "confirmed", "exitCode": 0,
        })

    async def test_idle_timeout_does_not_cut_off_a_claimed_call(self):
        waiter = self.task(self.host.wait_for_stop(idle_timeout=0.01))
        await asyncio.sleep(0)
        await self.host.offer(FakeRequest())
        await asyncio.sleep(0.03)
        self.assertFalse(waiter.done())
        self.assertFalse(self.host.failed)
        self.host.request_stop()
        await waiter

    async def test_unused_host_deadline_closes_admission_without_allocation(self):
        await self.host.wait_for_stop(idle_timeout=0.01)
        self.assertTrue(self.host.failed)
        self.assertEqual((await self.host.offer(FakeRequest())).status, 503)
        self.assertEqual(self.http.posts, [])
        self.assertIn({"event": "probe_failed", "reason": "host_idle_deadline"}, self.metadata())

    async def test_oversized_answer_is_bounded_and_known_call_is_still_hung_up(self):
        self.http.answer = b"x" * (fixture.MAX_SDP_BYTES + 64)
        with self.assertRaisesRegex(fixture.ProbeFailure, "^invalid_answer_size$"):
            await self.host.offer(FakeRequest())
        self.assertEqual(self.http.read_sizes, [fixture.MAX_SDP_BYTES + 1])
        await self.host.shutdown()
        self.assertTrue(self.host.hangup_confirmed)
        self.assertEqual(self.host.exit_code, 1)

    async def test_environment_preflight_rejects_missing_capability_or_key_before_http(self):
        cases = (
            ({}, "test_capability_token_required"),
            ({"BUDDYSTUDY_NATIVE_CONVERSATION_TOKEN": TEST_TOKEN}, "openai_api_key_required"),
            ({"BUDDYSTUDY_NATIVE_CONVERSATION_TOKEN": TEST_TOKEN,
              "OPENAI_API_KEY": "bad key"}, "openai_api_key_required"),
        )
        for environment, reason in cases:
            with self.subTest(reason=reason), mock.patch.dict(os.environ, environment, clear=True):
                with self.assertRaisesRegex(fixture.ProbeFailure, "^" + reason + "$"):
                    await fixture.serve(None)

    async def test_cli_preflight_rejects_unarmed_public_wildcard_or_backend_port(self):
        cases = ([], ["--run", "--bind", "0.0.0.0"],
                 ["--run", "--bind", "8.8.8.8"], ["--run", "--port", "8080"])
        for arguments in cases:
            with self.subTest(arguments=arguments), mock.patch("sys.argv", [str(SCRIPT)] + arguments):
                with mock.patch.dict(os.environ, {}, clear=True), contextlib.redirect_stderr(io.StringIO()):
                    with self.assertRaises(SystemExit) as result:
                        runpy.run_path(str(SCRIPT), run_name="__main__")
            self.assertEqual(result.exception.code, 2)


if __name__ == "__main__":
    unittest.main()
