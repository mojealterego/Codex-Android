FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PORT=8080 \
    CODEX_STATE_DB=/data/codex-android-state.sqlite3

WORKDIR /srv/codex

RUN useradd --create-home --uid 10001 codex \
    && mkdir -p /data \
    && chown -R codex:codex /data /srv/codex

COPY requirements.txt ./requirements.txt
RUN python -m pip install --upgrade pip \
    && python -m pip install -r requirements.txt

COPY app ./app

USER codex

EXPOSE 8080

CMD ["sh", "-c", "exec uvicorn app.server:create_runtime_app --factory --host 0.0.0.0 --port ${PORT:-8080}"]
