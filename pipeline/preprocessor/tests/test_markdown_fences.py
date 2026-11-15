import unittest

from pipeline.preprocessor.markdown_fences import (
    strip_known_response_prose,
    strip_markdown_fences,
)


class MarkdownFenceTests(unittest.TestCase):
    def test_repeated_stripping_returns_identical_preprocessed_source(self):
        sources = (
            "",
            "public class Map {}",
            "```java\npublic class Map {}\n```\n",
            " \t```JAVA\r\npublic class Map {}\r\n \t```\r\n",
            '```java\nclass Map { String fence = "```java"; }\n```\n',
            '```java\nclass Map {\nString text = """\n```java\n```\n""";\n}\n```\n',
            "```java\n/*\n```java\n```\n*/\nclass Map {}\n```\n",
        )
        for source in sources:
            with self.subTest(source=source):
                processed = strip_markdown_fences(source)
                for _ in range(3):
                    self.assertEqual(strip_markdown_fences(processed), processed)

    def test_removes_standalone_java_and_unlabelled_fences(self):
        for opening, newline in (
            ("```java", "\n"), ("```", "\n"), ("  ```JAVA \t", "\r\n"),
        ):
            with self.subTest(opening=opening, newline=newline):
                source = f"{opening}{newline}public class Map {{}}{newline}```"
                expected = f"public class Map {{}}{newline}"
                self.assertEqual(strip_markdown_fences(source), expected)
                self.assertEqual(strip_markdown_fences(expected), expected)

    def test_fence_removal_preserves_comments_literals_and_other_content(self):
        source = '''// ```java
/*
```java
```
*/
class Map {
    String inline = "```java";
    String block = """
        ```java
        example
        ```
        """;
}
Prose is not removed.
'''
        self.assertEqual(strip_markdown_fences(source), source)

    def test_extracts_single_block_despite_apostrophes_in_surrounding_prose(self):
        for newline in ("\n", "\r\n"):
            with self.subTest(newline=newline):
                body = f"public class Map {{}}{newline}"
                source = (
                    f"Here's the implementation. I'll keep it short.{newline}"
                    f"```java{newline}{body}```{newline}"
                    "It's ready."
                )
                self.assertEqual(strip_markdown_fences(source), body)
                self.assertEqual(strip_markdown_fences(body), body)

    def test_prose_quotes_cannot_protect_fences_across_lines(self):
        source = '"Here is the code:\n```java\nclass Map {}\n```\nDone."\n'
        self.assertEqual(strip_markdown_fences(source), "class Map {}\n")

    def test_extracts_block_with_package_private_and_semicolon_in_prose(self):
        prose = (
            "HarmonicBufferedStream.java and its helper types, all in one file:\n\n"
            "package-private interfaces and classes are top-level; "
            "the file has one public class.\n\n"
        )
        for newline in ("\n", "\r\n"):
            for before in (True, False):
                with self.subTest(newline=newline, before=before):
                    body = f"public class HarmonicBufferedStream {{}}{newline}"
                    block = f"```java{newline}{body}```{newline}"
                    outside = prose.replace("\n", newline)
                    source = outside + block if before else block + outside
                    self.assertEqual(strip_markdown_fences(source), body)

    def test_combines_import_and_java_blocks_with_prose_between_them(self):
        source = (
            "```java\nimport java.util.List;\n```\n"
            "The import above is all the setup needed.\n"
            "```java\npublic class Map {}\n```\n"
        )
        self.assertEqual(
            strip_markdown_fences(source),
            "import java.util.List;\npublic class Map {}\n",
        )

    def test_combines_unlabelled_import_and_java_blocks(self):
        source = "```\nimport java.util.List;\n```\n```\nclass Map {}\n```"
        self.assertEqual(
            strip_markdown_fences(source),
            "import java.util.List;\nclass Map {}\n",
        )

    def test_rejects_multiple_fenced_type_declarations(self):
        source = "```java\nclass Map {}\n```\n```java\nclass Helper {}\n```\n"
        with self.assertRaisesRegex(ValueError, "Cannot safely combine"):
            strip_markdown_fences(source)

    def test_removes_only_known_response_prose(self):
        source = (
            "package-private-free single file below; @Map.ThreadSafe\n"
            "no markdown fences.\n"
            "public class Map {}\n"
        )
        self.assertEqual(
            strip_known_response_prose(source),
            "@Map.ThreadSafe\npublic class Map {}\n",
        )
        ordinary = '// package-private\nString note = "no markdown fences.";\n'
        self.assertEqual(strip_known_response_prose(ordinary), ordinary)

    def test_normalizes_package_private_type_description(self):
        self.assertEqual(
            strip_known_response_prose(
                "package-private (default-access) top-level interface Order<T> {}\n"
            ),
            "interface Order<T> {}\n",
        )

    def test_rejects_multiple_incomplete_and_mixed_language_blocks(self):
        for source in (
            "```java\nclass Map {}\n```\n```java\nclass Map {}\n```\n",
            "```\nclass Map {}\n```\n```\nclass Helper {}\n```",
            "```java\nclass Map {}\n",
            "```java\nclass Map {}\n```java\n",
            "```python\nprint('example')\n```\n```java\nclass Map {}\n```\n",
            "```java\nclass Map {}\n```\n```python\nprint('example')\n```\n",
        ):
            with self.subTest(source=source):
                with self.assertRaises(ValueError):
                    strip_markdown_fences(source)

    def test_rejects_other_language_blocks(self):
        source = "Example:\n```python\nprint('hello')\n```\n"
        with self.assertRaises(ValueError):
            strip_markdown_fences(source)

    def test_preserves_real_characters_comments_and_text_blocks_inside_fences(self):
        body = (
            "class Map {\n"
            "char quote = '\\''; char newline = '\\n'; char brace = '}';\n"
            "char unicode = '\\u0041'; char octal = '\\141';\n"
            'String text = """\n```java\n```\n""";\n'
            "/*\n```java\n```\n*/\n"
            "}\n"
        )
        self.assertEqual(strip_markdown_fences(f"```java\n{body}```\n"), body)


if __name__ == "__main__":
    unittest.main()
