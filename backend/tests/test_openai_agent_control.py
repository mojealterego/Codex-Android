from types import SimpleNamespace

from app.openai_agent_control import OpenAIAgentControl


class FakeEvents:
    def __init__(self):
        self.calls = []

    def create(self, session_id, **kwargs):
        self.calls.append((session_id, kwargs))
        return SimpleNamespace(ok=True)


class FakeClient:
    def __init__(self):
        self.events = FakeEvents()
        self.beta = SimpleNamespace(
            agents=SimpleNamespace(
                sessions=SimpleNamespace(events=self.events)
            )
        )


def test_steer_sends_idempotent_user_message_event():
    client = FakeClient()
    control = OpenAIAgentControl(client)

    control.steer(
        "sess_123",
        "Keep the API compatible.",
        idempotency_key="steer-001",
    )

    assert client.events.calls == [
        (
            "sess_123",
            {
                "idempotency_key": "steer-001",
                "events": [{
                    "type": "agent.session.input.message",
                    "input": [{
                        "role": "user",
                        "content": [{
                            "type": "input_text",
                            "text": "Keep the API compatible.",
                        }],
                    }],
                }],
            },
        )
    ]


def test_cancel_sends_cancel_event():
    client = FakeClient()
    control = OpenAIAgentControl(client)

    control.cancel("sess_123")

    assert client.events.calls == [
        (
            "sess_123",
            {
                "events": [{
                    "type": "agent.session.input.cancel",
                }],
            },
        )
    ]


def test_steer_rejects_blank_message_and_key():
    control = OpenAIAgentControl(FakeClient())

    for message, key in [
        ("", "key"),
        ("   ", "key"),
        ("message", ""),
        ("message", "  "),
    ]:
        try:
            control.steer("sess_123", message, idempotency_key=key)
            raise AssertionError("Expected ValueError")
        except ValueError:
            pass
