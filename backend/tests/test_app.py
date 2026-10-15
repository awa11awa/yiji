import os
from datetime import datetime
import pytest
from fastapi.testclient import TestClient
from app import app, ExtractionRequest, ExtractedItem, local_extract

client = TestClient(app)


@pytest.fixture(autouse=True)
def no_remote_model(monkeypatch):
    for key in ("MODEL_API_KEY", "MODEL_NAME", "YIJI_ACCESS_TOKEN"): monkeypatch.delenv(key, raising=False)


def request(text, title=""):
    return {"title": title, "text": text, "received_at": "2026-10-14T12:00:00+08:00", "timezone": "Asia/Shanghai"}


def test_schedule_and_afternoon_range():
    response = client.post("/v1/extract", json=request("明天下午2点到3点在图书馆301召开竞赛培训。请携带电脑。"))
    assert response.status_code == 200
    item = response.json()["item"]
    assert item["title"] == "竞赛培训"
    assert item["start_at"] == "2026-10-15T14:00:00+08:00"
    assert item["end_at"] == "2026-10-15T15:00:00+08:00"
    assert item["location"] == "图书馆301"


def test_deadline_is_not_a_schedule():
    item = client.post("/v1/extract", json=request("请于本周五23:59前提交数学作业", "数学作业")).json()["item"]
    assert item["kind"] == "DEADLINE"
    assert item["due_at"] == "2026-10-16T23:59:00+08:00"
    assert item["start_at"] is None and item["end_at"] is None


def test_missing_information_is_not_invented():
    item = client.post("/v1/extract", json=request("竞赛培训即将举行，具体时间另行通知", "竞赛培训")).json()["item"]
    assert item["start_at"] is None and item["end_at"] is None
    assert len(item["issues"]) >= 2


def test_update_uses_new_time():
    item = local_extract(ExtractionRequest(**request("原定今天14:00至15:00的会议延期至后天16:00至17:00", "班级会议")))
    assert item.start_at.day == 16 and item.start_at.hour == 16
    assert item.end_at.hour == 17


def test_invalid_date_stays_unconfirmed():
    item = local_extract(ExtractionRequest(**request("2026年2月30日14:00至15:00会议", "会议")))
    assert item.start_at is None


def test_service_token_required(monkeypatch):
    monkeypatch.setenv("YIJI_ACCESS_TOKEN", "test-secret")
    assert client.post("/v1/extract", json=request("明天14:00会议")).status_code == 401
    assert client.post("/v1/extract", json=request("明天14:00会议"), headers={"Authorization": "Bearer test-secret"}).status_code == 200


def test_timezone_and_payload_validation():
    invalid = request("通知")
    invalid["timezone"] = "not/a-timezone"
    assert client.post("/v1/extract", json=invalid).status_code == 422
    naive = request("通知")
    naive["received_at"] = "2026-10-14T12:00:00"
    assert client.post("/v1/extract", json=naive).status_code == 422
    assert client.post("/v1/extract", json=request("字" * 16001)).status_code == 422


def test_model_result_validation():
    with pytest.raises(ValueError): ExtractedItem(title="错误", kind="DEADLINE", start_at=datetime.fromisoformat("2026-10-14T12:00:00+08:00"))
    with pytest.raises(ValueError): ExtractedItem(title="错误", kind="SCHEDULE", start_at=datetime(2026, 10, 14, 12))
    reversed_time = ExtractedItem(title="会议", kind="SCHEDULE", start_at=datetime.fromisoformat("2026-10-14T15:00:00+08:00"), end_at=datetime.fromisoformat("2026-10-14T14:00:00+08:00"))
    assert reversed_time.end_at is None and reversed_time.issues


def test_health():
    assert client.get("/health").json() == {"status": "ok", "version": "1.0.0", "model_configured": False}


def mock_model(monkeypatch, target_id="existing-1", confidence=0.9):
    import app as module
    import json
    from io import BytesIO
    monkeypatch.setenv("MODEL_API_KEY", "test-model-key")
    monkeypatch.setenv("MODEL_NAME", "test-model")
    monkeypatch.setenv("MODEL_BASE_URL", "https://model.example/v1")
    content = {"item": {"title": "信息竞赛辅导", "category": "活动", "kind": "SCHEDULE",
        "start_at": "2026-10-16T16:00:00+08:00", "end_at": "2026-10-16T17:00:00+08:00"},
        "association": {"type": "UPDATE", "target_id": target_id, "reason": "主题相同，原文明确延期", "confidence": confidence}}
    payload = json.dumps({"choices": [{"message": {"content": json.dumps(content)}}]}).encode()
    monkeypatch.setattr(module.request, "urlopen", lambda *args, **kwargs: BytesIO(payload))
    data = request("信息竞赛辅导推迟至后天16:00至17:00")
    data["existing_items"] = [{"id": "existing-1", "title": "竞赛培训", "kind": "SCHEDULE", "category": "活动"}]
    return data


def test_semantic_update_suggestion(monkeypatch):
    data = mock_model(monkeypatch)
    response = client.post("/v1/extract", json=data).json()
    assert response["engine"] == "model"
    assert response["association"]["type"] == "UPDATE"
    assert response["association"]["target_id"] == "existing-1"


def test_unknown_model_target_rejected(monkeypatch):
    response = client.post("/v1/extract", json=mock_model(monkeypatch, target_id="invented-id")).json()
    assert response["association"] is None
    assert response["item"]["issues"]


def test_low_confidence_model_target_rejected(monkeypatch):
    response = client.post("/v1/extract", json=mock_model(monkeypatch, confidence=0.4)).json()
    assert response["association"] is None
