# agent-service image. deploy/k3d/up.sh builds it:
#   docker build --build-arg PYTHON_IMAGE=... --build-arg UV_IMAGE=... services/agent-service
ARG UV_IMAGE
ARG PYTHON_IMAGE
FROM ${UV_IMAGE} AS uv

FROM ${PYTHON_IMAGE}
COPY --from=uv /uv /usr/local/bin/uv
WORKDIR /app
# Dependencies first, so a code change does not reinstall them.
COPY pyproject.toml uv.lock .python-version ./
RUN UV_PYTHON_DOWNLOADS=never uv sync --frozen --no-dev --no-cache
COPY app app
COPY migrations migrations
USER 10001
ENV PATH="/app/.venv/bin:$PATH"
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8084"]
