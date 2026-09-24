#!/bin/sh
# Проверка приложения хоста (для CI): запускает собранную оболочку, ждёт, пока встроенный сервер
# ответит, и что окно получило ссылку входа. Ловит ошибки, которые видны только при настоящем
# запуске (пути Windows, упаковка Java и jar).
#
#   scripts/desktop-smoke.sh desktop/src-tauri/target/debug/groupbase-desktop
set -eu

BIN=$1
[ -f "$BIN.exe" ] && BIN="$BIN.exe"
DATA="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/groupbase-smoke-$$"
mkdir -p "$DATA"

GROUPBASE_DATA="$DATA" "$BIN" >"$DATA/shell.out" 2>&1 &

stop() {
	if command -v taskkill >/dev/null 2>&1; then
		taskkill //F //T //IM "$(basename "$BIN")" >/dev/null 2>&1 || true
	else
		pkill -f "$(basename "$BIN")" >/dev/null 2>&1 || true
	fi
}

fail() {
	echo "Ошибка: $1"
	stop
	for f in "$DATA/shell.out" "$DATA/logs/shell.log" "$DATA/logs/java.log"; do
		echo "== $f"
		cat "$f" 2>/dev/null || true
	done
	exit 1
}

port=""
for _ in $(seq 1 120); do
	p=$(sed -n 's/^port=//p' "$DATA/desktop.properties" 2>/dev/null || true)
	if [ -n "$p" ] && curl -sf -o /dev/null "http://127.0.0.1:$p/api/health"; then
		port=$p
		break
	fi
	sleep 1
done
[ -n "$port" ] || fail "сервер приложения не ответил за 2 минуты"

curl -sf -D - -o /dev/null "http://127.0.0.1:$port/" | grep -qi '^x-groupbase: 1' ||
	fail "главная страница пришла не от сервера groupbase"

for _ in $(seq 1 30); do
	grep -q "api/desktop/enter" "$DATA/logs/shell.log" 2>/dev/null && break
	sleep 1
done
grep -q "api/desktop/enter" "$DATA/logs/shell.log" 2>/dev/null ||
	fail "окно приложения не получило ссылку входа"

stop
echo "Приложение запустилось: сервер на порту $port, окно открыло сайт."
