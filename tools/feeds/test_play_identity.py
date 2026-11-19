"""Tests of the rejected-identity check (#1564). Google and GitHub are fakes; no token is real."""
from __future__ import annotations

import os
import unittest
from unittest import mock

import play_identity_refused as check

PROVIDER = "projects/123/locations/global/workloadIdentityPools/github/providers/giglog"
REFUSAL = {"error": "unauthorized_client",
           "error_description": "The given credential is rejected by the attribute condition."}
ENV = {"PLAY_WIF_PROVIDER": PROVIDER, "PLAY_SERVICE_ACCOUNT": "sa@example.test",
       "ACTIONS_ID_TOKEN_REQUEST_URL": "https://token.example.test/?x=1", "ACTIONS_ID_TOKEN_REQUEST_TOKEN": "t"}


class PlayIdentityTest(unittest.TestCase):
    def run_main(self, answer, env=ENV, fork=False):
        fork_answer = {"side_effect": fork} if isinstance(fork, BaseException) else {"return_value": fork}
        with mock.patch.dict(os.environ, env, clear=True), \
                mock.patch.object(check, "is_fork", **fork_answer), \
                mock.patch.object(check, "github_identity_token", return_value="fake.jwt.value"), \
                mock.patch.object(check, "exchange", return_value=answer) as exchange, \
                mock.patch("builtins.print") as printed:
            code = check.main()
        return code, " ".join(str(c.args[0]) for c in printed.call_args_list), exchange

    def test_the_attribute_condition_refusal_passes(self):
        code, out, exchange = self.run_main((400, REFUSAL))
        self.assertEqual(0, code)
        self.assertIn("refused", out)
        self.assertEqual(PROVIDER, exchange.call_args.args[0])

    def test_an_issued_token_fails_and_is_never_printed(self):
        code, out, _ = self.run_main((200, {"access_token": "SECRET-TOKEN-VALUE", "token_type": "Bearer"}))
        self.assertEqual(1, code)
        self.assertNotIn("SECRET-TOKEN-VALUE", out)
        self.assertNotIn("fake.jwt.value", out)

    def test_any_other_refusal_an_outage_or_a_token_fails(self):
        for status, body in [
            (400, {"error": "invalid_request", "error_description": "Invalid value for audience."}),
            (400, {"error": "invalid_target", "error_description": "The target service indicated is invalid."}),
            (400, {"error": "invalid_grant", "error_description": "The audience in ID Token does not match."}),
            (403, REFUSAL),
            (400, {**REFUSAL, "access_token": "x"}),
            (503, {}),
            (403, {}),
            (200, {}),
        ]:
            with self.subTest(status=status, body=body):
                self.assertEqual(1, self.run_main((status, body))[0])

    def test_no_play_login_passes_only_in_a_fork(self):
        code, out, exchange = self.run_main((200, {"access_token": "x"}), env={}, fork=True)
        self.assertEqual(0, code)
        self.assertIn("nothing to check", out)
        exchange.assert_not_called()
        code, out, _ = self.run_main((400, REFUSAL), env={}, fork=False)
        self.assertEqual(1, code)
        self.assertIn("not a fork", out)
        self.assertEqual(1, self.run_main((400, REFUSAL), env={}, fork=OSError("down"))[0])

    def test_half_a_configuration_or_no_id_token_fails(self):
        self.assertEqual(1, self.run_main((400, {"error": "x"}), env={"PLAY_WIF_PROVIDER": PROVIDER})[0])
        no_token = {k: v for k, v in ENV.items() if not k.startswith("ACTIONS_")}
        self.assertEqual(1, self.run_main((400, {"error": "x"}), env=no_token)[0])

    def test_the_provider_variable_may_carry_a_prefix(self):
        for raw in (PROVIDER, "//iam.googleapis.com/" + PROVIDER, "https://iam.googleapis.com/" + PROVIDER + "/"):
            self.assertEqual(PROVIDER, check.provider_path(raw))

    def test_the_exchange_asks_for_the_provider_audience(self):
        seen = {}

        def post(body):
            seen["body"] = body
            return 400, {"error": "invalid_grant"}

        self.assertEqual(400, check.exchange(PROVIDER, "jwt", post)[0])
        self.assertIn(b'"audience": "//iam.googleapis.com/' + PROVIDER.encode(), seen["body"])


if __name__ == "__main__":
    unittest.main()
