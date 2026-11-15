import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from pipeline.prompt_generator.advanced_datastructures import advanced_datastructures
from pipeline.prompt_generator.base_prompt import implementation_requirements, prompt_intro
from pipeline.prompt_generator.datastructures import datastructures
from pipeline.prompt_generator.generator import build_prompts, write_prompts
from pipeline.prompt_generator.novel_problems import novel_problems
from pipeline.prompt_generator.problems import PROBLEM_CATEGORIES, Problem


class PromptGeneratorTests(unittest.TestCase):
    def test_prompt_names_are_unique_and_stable_across_builds(self):
        prompts = build_prompts()
        names = [record["name"] for record in prompts]
        self.assertEqual(len(names), len(set(names)))
        self.assertEqual(names, [record["name"] for record in build_prompts()])
        self.assertEqual(names[0], "datastructures/map")

    def test_same_problem_name_in_different_categories_has_distinct_prompt_names(self):
        with patch.dict(PROBLEM_CATEGORIES, {
            "first": [Problem("SharedName", "First problem.")],
            "second": [Problem("SharedName", "Second problem.")],
        }, clear=True):
            self.assertEqual(
                [record["name"] for record in build_prompts()],
                ["first/SharedName", "second/SharedName"],
            )

    def test_duplicate_prompt_names_are_rejected(self):
        with patch.dict(PROBLEM_CATEGORIES, {
            "coordination": [
                Problem("Barrier", "First description."),
                Problem("Barrier", "Second description."),
            ],
        }), self.assertRaisesRegex(ValueError, "Duplicate prompt name"):
            build_prompts()

    def test_builds_one_prompt_per_data_structure(self):
        prompts = build_prompts()
        structures = [p for p in prompts if p["category"] == "datastructures"]

        self.assertEqual([p["datastructure"] for p in structures], datastructures)
        for record in structures:
            self.assertIn(
                f"Implement a thread-safe concurrent version of the "
                f"{record['datastructure']} data structure in Java.",
                record["prompt"],
            )
            self.assertNotIn("source", record)
        self.assertIn("the map data structure", prompts[0]["prompt"])

    def test_every_prompt_has_shared_intro_problem_and_requirements_in_order(self):
        for record in build_prompts():
            with self.subTest(problem=record["datastructure"]):
                intro, body = record["prompt"].split("\n\nProblem:\n", 1)
                problem, requirements = body.rsplit("\n\nRequirements:\n", 1)
                self.assertEqual(intro, prompt_intro)
                self.assertTrue(problem.strip())
                self.assertEqual(
                    "Requirements:\n" + requirements, implementation_requirements
                )
                self.assertEqual(record["prompt"].count(implementation_requirements), 1)
                self.assertEqual(record["prompt"].count(prompt_intro), 1)
                self.assertEqual(record["prompt"].count("\n\nRequirements:\n"), 1)
                self.assertNotIn("$", record["prompt"])

    def test_advanced_hash_map_preserves_the_contract_with_shared_requirements(self):
        records = [
            record for record in build_prompts()
            if record["name"] == "advanced_datastructures/MysteriousHashMap"
        ]
        self.assertEqual(
            [record["name"] for record in records],
            ["advanced_datastructures/MysteriousHashMap"],
        )
        prompt = records[0]["prompt"]
        for signature in (
            "boolean containsKey(K k);", "V get(K k);", "V put(K k, V v);",
            "V remove(K k);", "int size();", "void forEach(BiConsumer<K,V> consumer);",
        ):
            self.assertIn(signature, prompt)
        self.assertIn("no IMap interface declaration or implements clause", prompt)
        self.assertIn("Each bucket is an immutable list of immutable key-value pairs.", prompt)
        self.assertIn("containsKey, get, and forEach must proceed without taking a lock", prompt)
        self.assertIn("The other operations must use locking.", prompt)
        self.assertIn("reallocating an individual stripe's bucket array", prompt)
        self.assertTrue(prompt.endswith(implementation_requirements))

    def test_advanced_structures_preserve_contracts_with_shared_requirements(self):
        names = [
            "TreeSet", "TreeBag", "TreeDictionary", "IntervalHeap",
            "HashedArrayList", "binary_search_tree", "red_black_tree", "b_tree",
            "HashedLinkedList",
        ]
        records = [
            record for record in build_prompts()
            if record["category"] == "advanced_datastructures"
        ]
        self.assertEqual(
            [record["datastructure"] for record in records],
            ["MysteriousHashMap", *names],
        )
        definitions = {problem["name"]: problem for problem in advanced_datastructures}
        for record in records[1:]:
            with self.subTest(problem=record["datastructure"]):
                name = record["datastructure"]
                definition = definitions[name]
                self.assertEqual(record["name"], f"advanced_datastructures/{name}")
                self.assertEqual(
                    record["prompt"],
                    f"{prompt_intro}\n\nProblem:\n{definition['description']}"
                    f"\n\n{definition['contract']}\n\n{implementation_requirements}",
                )

    def test_common_collections_remain_regular_and_specialized_trees_are_advanced(self):
        records = {record["name"]: record for record in build_prompts()}
        for name in (
            "skip_list_map", "skip_list_set", "delay_queue", "priority_queue",
            "dictionary", "bag",
        ):
            with self.subTest(problem=name):
                self.assertIn(f"datastructures/{name}", records)
        for name, class_name in (
            ("binary_search_tree", "BinarySearchTree"),
            ("red_black_tree", "RedBlackTree"),
            ("b_tree", "BTree"),
        ):
            with self.subTest(problem=name):
                self.assertNotIn(f"datastructures/{name}", records)
                record = records[f"advanced_datastructures/{name}"]
                self.assertIn(
                    f"Use {class_name} as the public top-level class name.",
                    " ".join(record["prompt"].split()),
                )
                self.assertIn("Contract:", record["prompt"])
        self.assertFalse(
            any(record["datastructure"] in {"dictionarybag", "b_tres"}
                for record in records.values())
        )

    def test_new_category_and_problems_use_the_same_renderer_and_retain_source(self):
        description = "Implement a shared ledger.\n\nPreserve literal $amount and {balance}."
        with patch.dict(PROBLEM_CATEGORIES, {
            "coordination": [
                Problem("Ledger", description, source="benchmark#ledger"),
                Problem("Barrier", "Implement a reusable barrier."),
            ],
        }):
            records = [p for p in build_prompts() if p["category"] == "coordination"]

        self.assertEqual([p["datastructure"] for p in records], ["Ledger", "Barrier"])
        self.assertEqual(records[0]["source"], "benchmark#ledger")
        self.assertNotIn("source", records[1])
        self.assertEqual(
            records[0]["prompt"],
            f"{prompt_intro}\n\nProblem:\n{description}\n\n{implementation_requirements}",
        )
        self.assertTrue(records[1]["prompt"].startswith(prompt_intro))
        self.assertTrue(records[1]["prompt"].endswith(implementation_requirements))

    def test_includes_exactly_the_twenty_top_ranked_novel_candidates(self):
        expected = {
            "HierarchicalScheduler": "ClassEval_228",
            "ThermostaticBuildOrchestrator": "ClassEval_143",
            "ResolvingVendingMachine": "ClassEval_170",
            "HarmonicBufferedStream": "ClassEval_290",
            "UserSpaceProvisioner": "ClassEval_0",
            "VersionedSlotManager": "ClassEval_26",
            "TaggedCapacityBuffer": "ClassEval_165",
            "AdaptiveTrustVault": "ClassEval_192",
            "SecureMetricAggregator": "ClassEval_108",
            "TemporalForexCache": "ClassEval_19",
            "BitmaskedTimelineStage": "ClassEval_255",
            "InventoryTransactionBuffer": "ClassEval_87",
            "ChatSessionStream": "ClassEval_145",
            "GomokuVersionState": "ClassEval_146",
            "ThermostaticCircuitStream": "ClassEval_32",
            "VerifiableMailbox": "ClassEval_211",
            "PersistedScheduleContext": "ClassEval_260",
            "LexicalGraphProvider": "ClassEval_66",
            "Warehouse": "ClassEval_95",
            "RPGCharacter": "ClassEval_73",
        }
        novels = [p for p in build_prompts() if p["category"] == "novel_problems"]
        self.assertEqual([p["datastructure"] for p in novels], list(expected))
        self.assertEqual(
            {p["datastructure"]: p["source"] for p in novels},
            {
                name: (
                    "prompts_from_other_benchmarks/"
                    + ("ClassEval_data.json" if name in {"Warehouse", "RPGCharacter"} else "data.json")
                    + f"#{task_id}"
                )
                for name, task_id in expected.items()
            },
        )
        for record in novels:
            with self.subTest(problem=record["datastructure"]):
                prompt = record["prompt"]
                self.assertIn(
                    f"Use {record['datastructure']} as the public top-level class name.",
                    prompt,
                )
                self.assertIn("Contract:", prompt)
                self.assertTrue(prompt.endswith(implementation_requirements))
                self.assertNotIn("$", prompt)
                self.assertIn("Do not import any libraries or packages.", prompt)
                self.assertIn("Do not use the synchronized modifier", prompt)
                original = next(
                    problem for problem in novel_problems
                    if problem["name"] == record["datastructure"]
                )
                self.assertIn(original["contract"], prompt)

    def test_writes_timestamped_structured_output(self):
        with tempfile.TemporaryDirectory() as directory:
            output = write_prompts(Path(directory))
            document = json.loads(output.read_text(encoding="utf-8"))

            self.assertRegex(
                output.name,
                r"^date:\d{4}-\d{2}-\d{2}T\d{2}-\d{2}\.json$",
            )
            self.assertIn("date", document["header"])
            self.assertIn("timestamp", document["header"])
            self.assertEqual(
                document["header"]["timestamp"],
                output.stem,
            )
            self.assertEqual(document["header"]["count"], len(document["prompts"]))
            self.assertEqual(document["prompts"], build_prompts())


if __name__ == "__main__":
    unittest.main()
