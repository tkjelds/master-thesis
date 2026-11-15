import re

from pipeline.preprocessor.java_tokens import JAVA_TOKENS

MARKDOWN_FENCE = re.compile(
    r"^[ \t]*```(?P<language>[^`\r\n]*?)[ \t]*(?:\r?\n|$)",
    re.MULTILINE,
)
JAVA_TYPE = re.compile(
    r"^[ \t]*(?:(?:public|protected|private|abstract|final|sealed|"
    r"non-sealed|static|strictfp)[ \t]+)*(?:class|interface|enum|record)"
    r"[ \t]+(?:[^\W\d]|\$)[\w$]*",
    re.MULTILINE,
)
JAVA_IMPORT = re.compile(
    r"^[ \t]*(?:package|import)[ \t]+[\w$.*]+;[ \t]*$",
    re.MULTILINE,
)
PROSE_LINES = {
    "package-private",
    "package-free source below:",
    "no markdown fences.",
    "import: none",
    "no fences, per your request.",
    "all access to shared state is guarded by one monitor.",
    "here is the source.",
    "import, unused in the source; no packages are imported.",
}
PROSE_PREFIXES = (
    "The code below compiles with `javac -Xlint:all` and passes a smoke test.",
    "The test covered chunked reads, EOF completion,",
)


def strip_markdown_fences(source: str) -> str:
    protected = [
        token.span() for token in JAVA_TOKENS.finditer(source)
        if token.lastgroup == "ignored"
    ]
    fences = [
        fence for fence in MARKDOWN_FENCE.finditer(source)
        if not any(
            start < fence.end() and end > fence.start()
            for start, end in protected
        )
    ]
    if not fences:
        return source
    if len(fences) < 2 or len(fences) % 2:
        raise ValueError("Expected one complete fenced Java block")

    blocks = []
    for index in range(0, len(fences), 2):
        opening, closing = fences[index:index + 2]
        if (
            opening.group("language").strip().lower() not in {"", "java"}
            or closing.group("language").strip()
        ):
            raise ValueError("Ambiguous Markdown fence languages")
        blocks.append(source[opening.end():closing.start()])
    if len(blocks) == 1:
        return blocks[0]

    type_blocks = [block for block in blocks if JAVA_TYPE.search(block)]
    if len(type_blocks) != 1 or any(
        block not in type_blocks
        and any(
            line.strip() and not JAVA_IMPORT.fullmatch(line.strip())
            for line in block.splitlines()
        )
        for block in blocks
    ):
        raise ValueError("Cannot safely combine multiple fenced Java blocks")

    combined = blocks[0]
    for block in blocks[1:]:
        if combined and not combined.endswith(("\n", "\r")) and block:
            combined += "\n"
        combined += block
    return combined


def strip_known_response_prose(source: str) -> str:
    cleaned = []
    for line in source.splitlines(keepends=True):
        content = line.rstrip("\r\n")
        ending = line[len(content):]
        indent = content[:len(content) - len(content.lstrip())]
        text = content[len(indent):]
        prefix = "package-private (default-access) top-level "
        if text.startswith(prefix) and text[len(prefix):].startswith("interface "):
            text = text[len(prefix):]
        else:
            for prefix in (
                "package-private-free single file below; ",
                "package-free source below; ",
                "import java.lang.*; is not needed; ",
            ):
                if text.startswith(prefix) and text[len(prefix):].startswith("@"):
                    text = text[len(prefix):]
                    break
        if text in PROSE_LINES or text.startswith(PROSE_PREFIXES):
            continue
        cleaned.append(indent + text + ending)
    return "".join(cleaned)
