import io
import json
import unittest
from unittest.mock import patch
from urllib.error import HTTPError, URLError

from pipeline.analysis.correctness.system_1 import System1Client, System1Error


def response(document):
    return io.BytesIO(json.dumps(document).encode())


def decision(probability=0.8):
    return {"answers": {"correctness": {"type": "noul", "noul": probability}}}


class System1ClientTests(unittest.TestCase):
    def setUp(self):
        self.client = System1Client()
        self.state = {"specification": "Implement addition", "java_source": "class Add {}"}

    @patch("pipeline.analysis.correctness.system_1.urlopen")
    def test_requests_native_noul_probability_without_generation_options(self, urlopen):
        urlopen.return_value = response(decision())
        self.assertEqual(
            self.client.probability(self.state, instructions="Functional only"), 0.8,
        )
        request = urlopen.call_args.args[0]
        self.assertEqual(request.full_url, "http://localhost:11434/v1/systemone")
        self.assertEqual(request.get_method(), "POST")
        self.assertEqual(request.get_header("Content-type"), "application/json")
        self.assertEqual(urlopen.call_args.kwargs, {"timeout": 815})
        self.assertEqual(json.loads(request.data), {
            "model": "kev:4b-mlx-bf16",
            "state": self.state,
            "questions": {
                "correctness": {
                    "type": "noul",
                    "instructions": "Functional only",
                    "criteria": {
                        "true": "The implementation satisfies the functional specification.",
                        "false": "The implementation has a functional defect or omits required behavior.",
                    },
                },
            },
        })

    @patch("pipeline.analysis.correctness.system_1.urlopen")
    def test_accepts_bounds_but_rejects_invalid_probabilities_without_clamping(self, urlopen):
        for probability in (0, 1, 0.12345):
            with self.subTest(probability=probability):
                urlopen.return_value = response(decision(probability))
                self.assertEqual(
                    self.client.probability(self.state, instructions="rubric"), probability,
                )
        for probability in (
            None, True, False, "0.8", -0.1, 1.1, float("nan"),
            float("inf"), -float("inf"), [], {}, 10**400,
        ):
            with self.subTest(probability=probability):
                urlopen.return_value = response(decision(probability))
                with self.assertRaisesRegex(System1Error, "finite number"):
                    self.client.probability(self.state, instructions="rubric")

    @patch("pipeline.analysis.correctness.system_1.urlopen")
    def test_rejects_invalid_responses_and_api_errors(self, urlopen):
        for document in (
            [], {}, {"answers": []}, {"answers": {"other": {"noul": 0.9}}},
            {"answers": {"correctness": {"type": "choice", "confidence": 0.9}}},
            {"error": "model loading failed"},
        ):
            with self.subTest(document=document):
                urlopen.return_value = response(document)
                with self.assertRaises(System1Error):
                    self.client.probability(self.state, instructions="rubric")
        urlopen.return_value = io.BytesIO(b'{"answers":')
        with self.assertRaises(ValueError):
            self.client.probability(self.state, instructions="rubric")

    @patch("pipeline.analysis.correctness.system_1.urlopen")
    def test_surfaces_transport_failures_without_retrying(self, urlopen):
        for error in (URLError("server unavailable"), TimeoutError("timed out")):
            with self.subTest(error=error):
                urlopen.reset_mock()
                urlopen.side_effect = error
                with self.assertRaises(OSError):
                    self.client.probability(self.state, instructions="rubric")
                urlopen.assert_called_once()
        urlopen.reset_mock()
        body = io.BytesIO(b'{"error": "input exceeds context"}')
        urlopen.side_effect = HTTPError("http://localhost", 400, "Bad Request", {}, body)
        with self.assertRaisesRegex(System1Error, "HTTP 400.*input exceeds context"):
            self.client.probability(self.state, instructions="rubric")
        urlopen.assert_called_once()
        self.assertTrue(body.closed)

    @patch("pipeline.analysis.correctness.system_1.urlopen")
    def test_fetches_model_digest_and_reports_missing_identity(self, urlopen):
        for name in ("clef-flash", "clef-flash:latest"):
            urlopen.return_value = response({
                "models": [{"name": "clef-flash:latest", "digest": "abc123"}],
            })
            self.assertEqual(System1Client(name).model_digest(), "abc123")
            request = urlopen.call_args.args[0]
            self.assertEqual(request.full_url, "http://localhost:11434/api/tags")
            self.assertEqual(request.get_method(), "GET")
        for document in (
            {}, {"models": []}, {"models": [{"name": "kev:4b-mlx-bf16"}]},
        ):
            with self.subTest(document=document):
                urlopen.return_value = response(document)
                with self.assertRaises(System1Error):
                    self.client.model_digest()


if __name__ == "__main__":
    unittest.main()
