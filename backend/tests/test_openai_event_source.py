from types import SimpleNamespace

from app.openai_event_source import OpenAIAgentEventSource


class FakeEvent:
    def __init__(self, payload):
        self.payload = payload

    def model_dump(self, mode="python"):
        assert mode == "json"
        return self.payload


class FakeStream:
    def __init__(self, events):
        self.events = events

    def __enter__(self):
        return iter(self.events)

    def __exit__(self, exc_type, exc, tb):
        return False


class FakeEventsApi:
    def __init__(self):
        self.last_session_id = None

    def stream(self, session_id):
        self.last_session_id = session_id
        return FakeStream([
            FakeEvent({
                "type": "agent.session.turn.output_text.delta",
                "session_id": session_id,
                "delta": "hello",
            }),
            FakeEvent({
                "type": "agent.session.idle",
                "session_id": session_id,
            }),
        ])


class FakeClient:
    def __init__(self):
        self.events_api = FakeEventsApi()
        self.beta = SimpleNamespace(
            agents=SimpleNamespace(
                sessions=SimpleNamespace(
                    events=self.events_api,
                )
            )
        )


def test_streams_openai_events_as_json_ready_dicts():
    client = FakeClient()
    source = OpenAIAgentEventSource(client)

    events = list(source.stream("sess_123"))

    assert client.events_api.last_session_id == "sess_123"
    assert events == [
        {
            "type": "agent.session.turn.output_text.delta",
            "session_id": "sess_123",
            "delta": "hello",
        },
        {
            "type": "agent.session.idle",
            "session_id": "sess_123",
        },
    ]
