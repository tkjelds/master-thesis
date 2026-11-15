from dataclasses import dataclass

from pipeline.prompt_generator.advanced_datastructures import advanced_datastructures
from pipeline.prompt_generator.datastructures import datastructures
from pipeline.prompt_generator.novel_problems import novel_problems


@dataclass(frozen=True)
class Problem:
    name: str
    description: str
    source: str | None = None


PROBLEM_CATEGORIES: dict[str, list[Problem]] = {
    "datastructures": [
        Problem(
            name=name,
            description=(
                f"Implement a thread-safe concurrent version of the {name} "
                "data structure in Java."
            ),
        )
        for name in datastructures
    ],
    "novel_problems": [
        Problem(
            name=problem["name"],
            description=(
                f"Implement the thread-safe {problem['name']} concurrency problem in Java.\n"
                f"Use {problem['name']} as the public top-level class name.\n\n"
                f"{problem['contract']}"
            ),
            source=problem["source"],
        )
        for problem in novel_problems
    ],
    "advanced_datastructures": [
        Problem(
            name=problem["name"],
            description=f"{problem['description']}\n\n{problem['contract']}",
        )
        for problem in advanced_datastructures
    ],
}
