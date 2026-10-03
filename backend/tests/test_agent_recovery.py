from types import SimpleNamespace

from app.agent_recovery import OpenAIAgentRecovery


class Dumpable:
    def __init__(self, payload):
        self.payload = payload

    def model_dump(self, mode="python"):
        assert mode == "json"
        return self.payload


class FakeItemsPage:
    def __init__(self, data):
        self.data = data
        self.has_more = False

    def __iter__(self):
        return iter(self.data)


class FakeItems:
    def list(self, session_id, *, order, limit):
        assert session_id == "sess_recover"
        assert order == "asc"
        assert limit == 100
        return FakeItemsPage([
            Dumpable({
                "id": "item_1",
                "type": "agent_message",
                "content": [{"type": "output_text", "text": "done"}],
            })
        ])


class FakeSessions:
    def __init__(self):
        self.items = FakeItems()

    def retrieve(self, session_id):
        assert session_id == "sess_recover"
        return SimpleNamespace(
            id=session_id,
            status="idle",
            error=None,
            required_actions=[
                Dumpable({"type": "function_call", "call_id": "call_1"})
            ],
        )


class FakeClient:
    def __init__(self):
        self.sessions = FakeSessions()
        self.beta = SimpleNamespace(
            agents=SimpleNamespace(
                sessions=self.sessions
            )
        )


def test_recovers_remote_session_state_required_actions_and_saved_items():
    result = OpenAIAgentRecovery(FakeClient()).recover("sess_recover")

    assert result.session_id == "sess_recover"
    assert result.status == "idle"
    assert result.error is None
    assert result.required_actions == (
        {"type": "function_call", "call_id": "call_1"},
    )
    assert result.items == (
        {
            "id": "item_1",
            "type": "agent_message",
            "content": [{"type": "output_text", "text": "done"}],
        },
    )
