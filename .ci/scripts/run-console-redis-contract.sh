#!/bin/sh

set -eu

redis_port=16379
redis_dir="$(mktemp -d)"
redis_log="$redis_dir/redis.log"
redis_pid=""

cleanup() {
  if [ -n "$redis_pid" ] && kill -0 "$redis_pid" 2>/dev/null; then
    kill "$redis_pid" 2>/dev/null || true
    wait "$redis_pid" 2>/dev/null || true
  fi
  rm -rf "$redis_dir"
}

trap cleanup EXIT HUP INT TERM

redis-server \
  --bind 127.0.0.1 \
  --protected-mode yes \
  --port "$redis_port" \
  --save "" \
  --appendonly no \
  --dir "$redis_dir" \
  --daemonize no \
  >"$redis_log" 2>&1 &
redis_pid=$!

redis_ready=false
attempt=1
while [ "$attempt" -le 30 ]; do
  if redis-cli -h 127.0.0.1 -p "$redis_port" ping 2>/dev/null | grep -qx PONG; then
    redis_ready=true
    break
  fi
  if ! kill -0 "$redis_pid" 2>/dev/null; then
    break
  fi
  attempt=$((attempt + 1))
  sleep 0.2
done

if [ "$redis_ready" != true ]; then
  echo "The isolated Redis contract fixture did not become ready." >&2
  tail -n 100 "$redis_log" >&2 || true
  exit 1
fi

FLOWWEFT_CONSOLE_TEST_REDIS_URL="redis://127.0.0.1:$redis_port/0" \
  npm run test:redis --prefix flowweft-console
