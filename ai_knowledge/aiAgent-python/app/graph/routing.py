from __future__ import annotations

from app.models.domain import AgentError, Evidence, SpecialistResult


def merge_specialist_results(
    results: list[SpecialistResult],
) -> tuple[list[Evidence], list[AgentError]]:
    evidence_by_key: dict[tuple[str, str], Evidence] = {}
    errors: list[AgentError] = []
    for result in results:
        errors.extend(result.errors)
        for item in result.evidence:
            key = (item.source_type, item.source_id)
            existing = evidence_by_key.get(key)
            if existing is None or (item.score or 0) > (existing.score or 0):
                evidence_by_key[key] = item
    priority = {"business": 0, "knowledge": 1, "web": 2}
    evidence = sorted(
        evidence_by_key.values(),
        key=lambda item: (item.demo, priority[item.source_type], -(item.score or 0), item.source_id),
    )
    return evidence, errors
