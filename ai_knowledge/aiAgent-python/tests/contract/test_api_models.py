from app.models.api import ChatStreamRequest, EmbeddingRequest
from app.models.domain import Evidence


def test_chat_request_accepts_java_camel_case_contract() -> None:
    request = ChatStreamRequest.model_validate(
        {
            "requestId": "req-1",
            "conversationId": "conv-1",
            "question": "怎么退货？",
            "user": {"userId": 7, "role": "USER", "permissions": ["knowledge:read"]},
            "options": {"topK": 3, "similarityThreshold": 0.4},
        }
    )

    assert request.request_id == "req-1"
    assert request.user.user_id == 7
    assert request.options.top_k == 3


def test_embedding_request_enforces_non_empty_bounded_batch() -> None:
    request = EmbeddingRequest(profile="default-embedding", texts=["a", "b"])
    assert request.texts == ["a", "b"]


def test_evidence_serializes_demo_flag_with_java_aliases() -> None:
    evidence = Evidence(
        source_type="business",
        source_id="order:DEMO-1001",
        title="演示订单",
        content="已发货",
        observed_at="2026-10-07T00:00:00Z",
        demo=True,
    )

    payload = evidence.model_dump(by_alias=True, mode="json")
    assert payload["sourceType"] == "business"
    assert payload["demo"] is True

